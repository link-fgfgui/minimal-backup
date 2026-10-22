package io.github.linkfgfgui.minimalbackup.engine;

import io.github.linkfgfgui.minimalbackup.engine.api.CodecRegistry;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * 快照树：节点表 + head + gen 计数器 + 每个块文件的缓存指针；落盘为 Properties。
 *
 * <p>{@code index.properties} 是唯一提交点：块先写、索引后写；索引里没有的快照
 * 和它的块都不可见。加载失败明确抛错，不自动重建。
 */
public final class Timeline {
    public static final int FORMAT_VERSION = 1;
    /**
     * 存储字节的规范化方案。与 {@code codec=} 的严格度相反：字段缺失（升级前写出的旧库）按
     * "未规范化"读下去，不拒载——未规范化的基底配上规范化的新值只让那一跳差分变差，不影响
     * 正确性；只有读到未知的非空值才整库拒载。
     */
    public static final String CANON = "nbt1";
    /** 祖先链上限，防止损坏的 parentSlot 形成环。 */
    public static final int MAX_CHAIN = 256;

    private static final char BACKSLASH = (char) 92;
    private static final char NEWLINE = (char) 10;
    private static final char CARRIAGE = (char) 13;
    private static final char TAB = (char) 9;

    private final Map<String, Snapshot> slots = new LinkedHashMap<>();
    private final Map<String, Long> cacheOffsets = new LinkedHashMap<>();
    private int nextGen = 1;
    private String head = "";

    private Timeline() {
    }

    /** 空时间线：gen 从 1 开始，head 为空。 */
    public static Timeline empty() {
        return new Timeline();
    }

    /** 加载索引；文件不存在返回空时间线，读不动抛 IOException。 */
    public static Timeline load(Path indexFile) throws IOException {
        if (indexFile == null) {
            throw new IllegalArgumentException("null index file");
        }
        Timeline timeline = new Timeline();
        if (!Files.exists(indexFile)) {
            return timeline;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(indexFile, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IllegalArgumentException e) {
            throw new IOException("corrupt index: " + indexFile, e);
        }
        String format = properties.getProperty("format");
        if (format != null) {
            int version;
            try {
                version = Integer.parseInt(format.trim());
            } catch (NumberFormatException e) {
                throw new IOException("bad format field in " + indexFile, e);
            }
            if (version != FORMAT_VERSION) {
                throw new IOException("unsupported index format " + version + ": " + indexFile);
            }
        }
        // 索引里没有 codec 字段的库，只可能是本体写出的 zstd 库
        String savedCodec = properties.getProperty("codec", CodecRegistry.DEFAULT_CODEC_ID);
        if (!savedCodec.equals(Codec.id())) {
            throw new IOException("Backup vault codec mismatch: vault was created with '" + savedCodec
                    + "', but current runtime uses '" + Codec.id()
                    + "'. Remove the old backup directory or install/remove the codec addon.");
        }
        String savedCanon = properties.getProperty("canon");
        if (savedCanon != null) {
            savedCanon = savedCanon.trim();
            if (!savedCanon.isEmpty() && !savedCanon.equals(CANON)) {
                throw new IOException("Backup vault canonicalisation mismatch: vault uses '"
                        + savedCanon + "', current runtime writes '" + CANON + "'.");
            }
        }
        timeline.nextGen = parseInt(properties.getProperty("gen"), 1, "gen", indexFile);
        if (timeline.nextGen < 1) {
            timeline.nextGen = 1;
        }
        timeline.head = properties.getProperty("head", "");
        if (timeline.head == null) {
            timeline.head = "";
        }

        Map<String, Snapshot.Builder> builders = new LinkedHashMap<>();
        for (String key : properties.stringPropertyNames()) {
            String value = properties.getProperty(key, "");
            if (key.startsWith("cache.")) {
                String path = key.substring("cache.".length());
                long offset = parseLong(value, -1L, key, indexFile);
                if (offset >= 0 && !path.isEmpty()) {
                    timeline.cacheOffsets.put(path, offset);
                }
            } else if (key.startsWith("slot.")) {
                String rest = key.substring("slot.".length());
                int dot = rest.lastIndexOf('.');
                if (dot <= 0 || dot == rest.length() - 1) {
                    continue;
                }
                String name = rest.substring(0, dot);
                String field = rest.substring(dot + 1);
                if (!VaultPaths.isValidSnapshotName(name)) {
                    throw new IOException("invalid snapshot name in index: " + name);
                }
                applyField(builders.computeIfAbsent(name, Snapshot.Builder::new), field, value, indexFile);
            }
        }
        for (Map.Entry<String, Snapshot.Builder> entry : builders.entrySet()) {
            Snapshot snapshot = entry.getValue().build();
            timeline.slots.put(entry.getKey(), snapshot);
            if (snapshot.generation() >= timeline.nextGen) {
                timeline.nextGen = snapshot.generation() + 1;
            }
        }
        if (!timeline.head.isEmpty() && !timeline.slots.containsKey(timeline.head)) {
            throw new IOException("head points at missing snapshot: " + timeline.head);
        }
        return timeline;
    }

    /** 原子替换写索引。 */
    public void save(Path indexFile) throws IOException {
        StringBuilder out = new StringBuilder(256 + slots.size() * 220 + cacheOffsets.size() * 60);
        out.append("format=").append(FORMAT_VERSION).append(NEWLINE);
        out.append("codec=").append(Codec.id()).append(NEWLINE);
        out.append("canon=").append(CANON).append(NEWLINE);
        out.append("gen=").append(nextGen).append(NEWLINE);
        out.append("head=").append(escapeValue(head)).append(NEWLINE);
        List<String> cacheKeys = new ArrayList<>(cacheOffsets.keySet());
        Collections.sort(cacheKeys);
        for (String key : cacheKeys) {
            out.append("cache.").append(escapeKey(key)).append('=').append(cacheOffsets.get(key)).append(NEWLINE);
        }
        for (Snapshot snapshot : slots.values()) {
            String prefix = "slot." + snapshot.slot() + ".";
            out.append(prefix).append("gen=").append(snapshot.generation()).append(NEWLINE);
            out.append(prefix).append("parentSlot=").append(escapeValue(snapshot.parentSlot())).append(NEWLINE);
            out.append(prefix).append("savedAt=").append(snapshot.savedAt()).append(NEWLINE);
            out.append(prefix).append("damaged=").append(snapshot.damaged()).append(NEWLINE);
            out.append(prefix).append("tombstone=").append(snapshot.tombstone()).append(NEWLINE);
            out.append(prefix).append("displayName=").append(escapeValue(snapshot.displayName())).append(NEWLINE);
            out.append(prefix).append("worldName=").append(escapeValue(snapshot.worldName())).append(NEWLINE);
            out.append(prefix).append("minecraftVersion=").append(escapeValue(snapshot.minecraftVersion())).append(NEWLINE);
            out.append(prefix).append("engineVersion=").append(escapeValue(snapshot.engineVersion())).append(NEWLINE);
            out.append(prefix).append("source=").append(escapeValue(snapshot.source())).append(NEWLINE);
            out.append(prefix).append("createdBy=").append(escapeValue(snapshot.createdBy())).append(NEWLINE);
            out.append(prefix).append("entryCount=").append(snapshot.entryCount()).append(NEWLINE);
            out.append(prefix).append("logicalBytes=").append(snapshot.logicalBytes()).append(NEWLINE);
            out.append(prefix).append("physicalBytes=").append(snapshot.physicalBytes()).append(NEWLINE);
        }
        Path parent = indexFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        AtomicFiles.write(indexFile, out.toString().getBytes(StandardCharsets.UTF_8));
    }

    public Snapshot snapshot(String slot) {
        return slot == null ? null : slots.get(slot);
    }

    public Collection<Snapshot> snapshots() {
        return Collections.unmodifiableCollection(slots.values());
    }

    public int size() {
        return slots.size();
    }

    public String head() {
        return head;
    }

    public void setHead(String slot) {
        this.head = slot == null ? "" : slot;
    }

    public int peekGen() {
        return nextGen;
    }

    /** 分配下一个快照代次并递增计数器。 */
    public int allocateGen() {
        return nextGen++;
    }

    /** 加入节点；名字非法或重复抛 IllegalArgumentException。 */
    public void add(Snapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("null snapshot");
        }
        if (!VaultPaths.isValidSnapshotName(snapshot.slot())) {
            throw new IllegalArgumentException("invalid snapshot name: " + snapshot.slot());
        }
        if (slots.containsKey(snapshot.slot())) {
            throw new IllegalArgumentException("duplicate snapshot: " + snapshot.slot());
        }
        slots.put(snapshot.slot(), snapshot);
        if (snapshot.generation() >= nextGen) {
            nextGen = snapshot.generation() + 1;
        }
    }

    /** 移除节点（仅用于提交失败时回滚内存状态）。 */
    public void remove(String slot) {
        slots.remove(slot);
    }

    /** 改显示名；槽位与代次不变，链保持完整。 */
    public void rename(String slot, String displayName) {
        Snapshot snapshot = slots.get(slot);
        if (snapshot == null) {
            throw new IllegalArgumentException("unknown snapshot: " + slot);
        }
        slots.put(slot, snapshot.withDisplayName(displayName));
    }

    /** 墓碑：保留节点与代次，只标记删除，避免子节点断链。 */
    public void tombstone(String slot) {
        Snapshot snapshot = slots.get(slot);
        if (snapshot == null) {
            throw new IllegalArgumentException("unknown snapshot: " + slot);
        }
        slots.put(slot, snapshot.asTombstone());
    }

    /** 目标快照的祖先链代次集合（含自己，上限 {@link #MAX_CHAIN}）。 */
    public Set<Integer> visibleGenerations(String slot) {
        Set<Integer> generations = new LinkedHashSet<>();
        String current = slot;
        int guard = 0;
        while (current != null && !current.isEmpty() && guard < MAX_CHAIN) {
            Snapshot snapshot = slots.get(current);
            if (snapshot == null) {
                break;
            }
            generations.add(snapshot.generation());
            current = snapshot.parentSlot();
            guard++;
        }
        return generations;
    }

    /** head 祖先链的代次集合；head 为空时为空集。 */
    public Set<Integer> liveGenerations() {
        if (head == null || head.isEmpty()) {
            return Collections.emptySet();
        }
        return visibleGenerations(head);
    }

    public Long cacheOffset(String key) {
        return cacheOffsets.get(key);
    }

    public void putCacheOffset(String key, long offset) {
        cacheOffsets.put(key, offset);
    }

    public void removeCacheOffset(String key) {
        cacheOffsets.remove(key);
    }

    public Set<String> cacheKeys() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(cacheOffsets.keySet()));
    }

    private static void applyField(Snapshot.Builder builder, String field, String value, Path indexFile)
            throws IOException {
        switch (field) {
            case "gen":
                builder.generation(parseInt(value, 0, field, indexFile));
                break;
            case "parentSlot":
                builder.parentSlot(value);
                break;
            case "savedAt":
                builder.savedAt(parseLong(value, 0L, field, indexFile));
                break;
            case "damaged":
                builder.damaged(Boolean.parseBoolean(value));
                break;
            case "tombstone":
                builder.tombstone(Boolean.parseBoolean(value));
                break;
            case "displayName":
                builder.displayName(value);
                break;
            case "worldName":
                builder.worldName(value);
                break;
            case "minecraftVersion":
                builder.minecraftVersion(value);
                break;
            case "engineVersion":
                builder.engineVersion(value);
                break;
            case "source":
                builder.source(value);
                break;
            case "createdBy":
                builder.createdBy(value);
                break;
            case "entryCount":
                builder.entryCount(parseInt(value, 0, field, indexFile));
                break;
            case "logicalBytes":
                builder.logicalBytes(parseLong(value, 0L, field, indexFile));
                break;
            case "physicalBytes":
                builder.physicalBytes(parseLong(value, 0L, field, indexFile));
                break;
            default:
                break;
        }
    }

    private static int parseInt(String value, int fallback, String field, Path indexFile) throws IOException {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IOException("bad " + field + " in " + indexFile, e);
        }
    }

    private static long parseLong(String value, long fallback, String field, Path indexFile) throws IOException {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IOException("bad " + field + " in " + indexFile, e);
        }
    }

    private static String escapeKey(String key) {
        StringBuilder out = new StringBuilder(key.length() + 8);
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            switch (c) {
                case BACKSLASH:
                    out.append(BACKSLASH).append(BACKSLASH);
                    break;
                case NEWLINE:
                    out.append(BACKSLASH).append('n');
                    break;
                case CARRIAGE:
                    out.append(BACKSLASH).append('r');
                    break;
                case '=':
                    out.append(BACKSLASH).append('=');
                    break;
                case ':':
                    out.append(BACKSLASH).append(':');
                    break;
                case ' ':
                    out.append(BACKSLASH).append(' ');
                    break;
                case '#':
                    out.append(BACKSLASH).append('#');
                    break;
                case '!':
                    out.append(BACKSLASH).append('!');
                    break;
                default:
                    out.append(c);
            }
        }
        return out.toString();
    }

    private static String escapeValue(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case BACKSLASH:
                    out.append(BACKSLASH).append(BACKSLASH);
                    break;
                case NEWLINE:
                    out.append(BACKSLASH).append('n');
                    break;
                case CARRIAGE:
                    out.append(BACKSLASH).append('r');
                    break;
                case TAB:
                    out.append(BACKSLASH).append('t');
                    break;
                default:
                    if (i == 0 && (c == ' ' || c == '#' || c == '!')) {
                        out.append(BACKSLASH);
                    }
                    out.append(c);
            }
        }
        return out.toString();
    }
}
