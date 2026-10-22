package io.github.linkfgfgui.minimalbackup.engine;

import com.github.luben.zstd.Zstd;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 非 NBT 的 JSON 文件：整文件 zstd，不做差分，不参与块链。
 *
 * <p>只覆盖「世界目录 / 一级目录 / *.json」（{@code stats/}、{@code advancements/}），
 * 与后续可能出现的同类布局。库布局：
 * {@code <snapshotRoot>/<相对路径>.json/<slot>.zst}；文件被删除时写
 * {@code <slot>.absent}。每个槽位只在内容变化时写一份完整副本，回滚时取不大于目标
 * 槽位代次的最新一份。文件很小，压缩产物直接逐字节比较即可判断是否变化。
 *
 * <p>纯 JDK + zstd-jni，无 MC/loader 依赖。
 */
public final class JsonStore {
    /** 压缩副本后缀。 */
    public static final String ZST_SUFFIX = ".zst";
    /** 删除标记后缀。 */
    public static final String ABSENT_SUFFIX = ".absent";
    /** 首个快照原样复制到库根的世界图标名。 */
    public static final String ICON_NAME = "icon.png";

    private static final byte[] EMPTY = new byte[0];

    private JsonStore() {
    }

    /** 一次建点要写的 JSON 变更；{@code null} 值表示该文件已被删除。 */
    public static final class Plan {
        private final Map<String, byte[]> entries = new LinkedHashMap<>();
        private long logicalBytes;
        private long physicalBytes;

        public boolean isEmpty() {
            return entries.isEmpty();
        }

        public long logicalBytes() {
            return logicalBytes;
        }

        public long physicalBytes() {
            return physicalBytes;
        }

        /** 仅供测试与诊断。 */
        public Map<String, byte[]> entries() {
            return Collections.unmodifiableMap(entries);
        }
    }

    /** 扫描世界目录并算出相对库中最新副本的变化。 */
    public static Plan plan(Path worldDir, Path snapshotRoot, int level) throws IOException {
        if (worldDir == null || snapshotRoot == null) {
            throw new IllegalArgumentException("null json store input");
        }
        Plan plan = new Plan();
        Map<String, Path> present = scan(worldDir);
        for (Map.Entry<String, Path> entry : present.entrySet()) {
            String key = entry.getKey();
            byte[] raw = Files.readAllBytes(entry.getValue());
            byte[] compressed = ZstdDelta.compressFull(raw, level);
            Version latest = latestVersion(snapshotRoot, key, Integer.MAX_VALUE);
            if (latest != null && !latest.absent && Arrays.equals(latest.bytes, raw)) {
                continue;
            }
            plan.entries.put(key, compressed);
            plan.logicalBytes += raw.length;
            plan.physicalBytes += compressed.length;
        }
        for (String key : knownKeys(snapshotRoot)) {
            if (present.containsKey(key)) {
                continue;
            }
            Version latest = latestVersion(snapshotRoot, key, Integer.MAX_VALUE);
            if (latest != null && latest.absent) {
                continue;
            }
            plan.entries.put(key, null);
        }
        return plan;
    }

    /** 写入一份计划；返回实际落盘的压缩字节数。 */
    public static long write(Path snapshotRoot, Plan plan, String slot) throws IOException {
        if (plan == null || plan.isEmpty()) {
            return 0L;
        }
        long written = 0L;
        for (Map.Entry<String, byte[]> entry : plan.entries.entrySet()) {
            String key = VaultPaths.normalizeKey(entry.getKey());
            Path dir = versionDir(snapshotRoot, key);
            Files.createDirectories(dir);
            byte[] data = entry.getValue();
            if (data == null) {
                AtomicFiles.write(dir.resolve(slot + ABSENT_SUFFIX), EMPTY);
                Files.deleteIfExists(dir.resolve(slot + ZST_SUFFIX));
            } else {
                AtomicFiles.write(dir.resolve(slot + ZST_SUFFIX), data);
                Files.deleteIfExists(dir.resolve(slot + ABSENT_SUFFIX));
                written += data.length;
            }
        }
        return written;
    }

    /**
     * 把目标代次可见的 JSON 写回世界目录：取每个键不大于 {@code targetGen} 的最新副本；
     * 该副本是 absent 标记时删除文件，没有任何副本的键不动。
     *
     * @return 实际写入/删除的文件数
     */
    public static int materialize(Path worldDir, Path snapshotRoot, int targetGen) throws IOException {
        int applied = 0;
        for (String key : knownKeys(snapshotRoot)) {
            Version version = latestVersion(snapshotRoot, key, targetGen);
            if (version == null) {
                continue;
            }
            Path target = worldDir.resolve(key).normalize();
            if (!target.startsWith(worldDir.normalize())) {
                throw new IOException("json key escapes the world: " + key);
            }
            if (version.absent) {
                Files.deleteIfExists(target);
                applied++;
            } else {
                Path parent = target.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                AtomicFiles.write(target, version.bytes);
                applied++;
            }
        }
        return applied;
    }

    /** 校验目标代次可见的 JSON 副本；计数合并进调用方的报告。 */
    public static void verify(Path snapshotRoot, int targetGen, long[] counters,
                              List<String> errors, int maxErrors) throws IOException {
        for (String key : knownKeys(snapshotRoot)) {
            try {
                Version version = latestVersion(snapshotRoot, key, targetGen);
                if (version == null) {
                    continue;
                }
                counters[0]++;
                if (version.absent) {
                    counters[1]++;
                } else {
                    counters[2]++;
                }
            } catch (IOException | RuntimeException e) {
                counters[0]++;
                counters[3]++;
                if (errors.size() < maxErrors) {
                    errors.add(key + ": " + e.getMessage());
                }
            }
        }
    }

    /**
     * 删除不再需要的 JSON 版本：保留最老活代次之前的最后一份，以及所有活代次及之后的
     * 版本。没有活代次时只保留最后一份。
     */
    public static int collectGarbage(Path snapshotRoot, int oldestLiveGen) throws IOException {
        int removed = 0;
        for (String key : knownKeys(snapshotRoot)) {
            Path dir = versionDir(snapshotRoot, key);
            List<VersionFile> files = versionFiles(dir);
            if (files.isEmpty()) {
                Files.deleteIfExists(dir);
                continue;
            }
            boolean atOrAbove = false;
            for (VersionFile file : files) {
                if (file.generation >= oldestLiveGen) {
                    atOrAbove = true;
                    break;
                }
            }
            int keepBelow = -1;
            if (!atOrAbove) {
                for (VersionFile file : files) {
                    if (file.generation < oldestLiveGen) {
                        keepBelow = file.generation;
                    }
                }
            }
            for (VersionFile file : files) {
                if (file.generation < oldestLiveGen && file.generation != keepBelow) {
                    Files.deleteIfExists(file.path);
                    removed++;
                }
            }
            if (isEmptyDirectory(dir)) {
                Files.delete(dir);
            }
        }
        return removed;
    }

    // ---------------------------------------------------------------- scanning

    /** 世界目录下一级目录里的 {@code *.json}，键为相对世界的正斜杠路径。 */
    static Map<String, Path> scan(Path worldDir) throws IOException {
        Map<String, Path> found = new LinkedHashMap<>();
        if (!Files.isDirectory(worldDir)) {
            return found;
        }
        List<Path> dirs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(worldDir)) {
            for (Path child : stream) {
                if (Files.isDirectory(child)) {
                    dirs.add(child);
                }
            }
        }
        Collections.sort(dirs);
        for (Path dir : dirs) {
            String folder = dir.getFileName().toString();
            if (VaultPaths.VAULT_DIR_NAME.equals(folder)
                    || VaultPaths.SNAPSHOT_DIR_NAME.equals(folder)) {
                continue;
            }
            List<Path> files = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path child : stream) {
                    String name = child.getFileName().toString();
                    if (Files.isRegularFile(child) && name.endsWith(".json")) {
                        files.add(child);
                    }
                }
            } catch (IOException e) {
                continue;
            }
            Collections.sort(files);
            for (Path file : files) {
                String key = folder + "/" + file.getFileName();
                try {
                    VaultPaths.normalizeKey(key);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                if (!VaultPaths.isExcluded(key)) {
                    found.put(key, file);
                }
            }
        }
        return found;
    }

    /** 库中所有 JSON 键（一级目录下以 {@code .json} 结尾的目录）。 */
    static List<String> knownKeys(Path snapshotRoot) throws IOException {
        List<String> keys = new ArrayList<>();
        if (!Files.isDirectory(snapshotRoot)) {
            return keys;
        }
        List<Path> dirs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(snapshotRoot)) {
            for (Path child : stream) {
                if (Files.isDirectory(child)) {
                    dirs.add(child);
                }
            }
        }
        Collections.sort(dirs);
        for (Path dir : dirs) {
            String folder = dir.getFileName().toString();
            if (VaultPaths.VAULT_DIR_NAME.equals(folder)
                    || VaultPaths.SNAPSHOT_DIR_NAME.equals(folder)) {
                continue;
            }
            List<Path> children = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path child : stream) {
                    if (Files.isDirectory(child)
                            && child.getFileName().toString().endsWith(".json")) {
                        children.add(child);
                    }
                }
            } catch (IOException e) {
                continue;
            }
            Collections.sort(children);
            for (Path child : children) {
                keys.add(folder + "/" + child.getFileName());
            }
        }
        return keys;
    }

    private static Path versionDir(Path snapshotRoot, String key) {
        return snapshotRoot.resolve(key);
    }

    private static final class VersionFile {
        private final int generation;
        private final Path path;
        private final boolean absent;

        private VersionFile(int generation, Path path, boolean absent) {
            this.generation = generation;
            this.path = path;
            this.absent = absent;
        }
    }

    private static final class Version {
        private final boolean absent;
        private final byte[] bytes;

        private Version(boolean absent, byte[] bytes) {
            this.absent = absent;
            this.bytes = bytes;
        }
    }

    /** 目录内全部合法版本，按代次升序。 */
    private static List<VersionFile> versionFiles(Path dir) throws IOException {
        List<VersionFile> files = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return files;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                String name = child.getFileName().toString();
                boolean absent;
                String number;
                if (name.endsWith(ZST_SUFFIX)) {
                    absent = false;
                    number = name.substring(0, name.length() - ZST_SUFFIX.length());
                } else if (name.endsWith(ABSENT_SUFFIX)) {
                    absent = true;
                    number = name.substring(0, name.length() - ABSENT_SUFFIX.length());
                } else {
                    continue;
                }
                int generation = parseSlot(number);
                if (generation < 0 || !Files.isRegularFile(child)) {
                    continue;
                }
                files.add(new VersionFile(generation, child, absent));
            }
        }
        Collections.sort(files, (left, right) -> Integer.compare(left.generation, right.generation));
        return files;
    }

    /** 不大于 {@code maxGen} 的最新版本；{@code null} 表示没有可用副本。 */
    private static Version latestVersion(Path snapshotRoot, String key, int maxGen)
            throws IOException {
        List<VersionFile> files = versionFiles(versionDir(snapshotRoot, key));
        VersionFile chosen = null;
        for (VersionFile file : files) {
            if (file.generation <= maxGen) {
                chosen = file;
            }
        }
        if (chosen == null) {
            return null;
        }
        if (chosen.absent) {
            return new Version(true, null);
        }
        byte[] compressed = Files.readAllBytes(chosen.path);
        return new Version(false, decompress(compressed));
    }

    private static byte[] decompress(byte[] compressed) throws IOException {
        try {
            long size = Zstd.getFrameContentSize(compressed, 0, compressed.length);
            if (size < 0 || size > Integer.MAX_VALUE) {
                throw new IOException("unusable zstd content size: " + size);
            }
            return Zstd.decompress(compressed, (int) size);
        } catch (RuntimeException e) {
            throw new IOException("zstd decompress failed", e);
        }
    }

    /** {@code S12} → 12；其他命名返回 -1。 */
    private static int parseSlot(String name) {
        if (name.length() < 2 || name.charAt(0) != 'S') {
            return -1;
        }
        int value = 0;
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            value = value * 10 + (c - '0');
            if (value < 0) {
                return -1;
            }
        }
        return value;
    }

    private static boolean isEmptyDirectory(Path dir) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            return !stream.iterator().hasNext();
        }
    }
}
