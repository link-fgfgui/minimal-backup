package io.github.linkfgfgui.minimalbackup.engine;

import io.github.linkfgfgui.minimalbackup.engine.nbt.NbtCanon;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 建点写入：ChangeSet → 按路径分组 → 追加块 → 写 Timeline（唯一提交点）。
 *
 * <p>崩在写索引之前时，新块代次不属于任何已提交快照，对解析不可见，
 * 之后由 {@link Compactor} 清理。
 */
public final class Checkpoint {
    private static final byte[] EMPTY = new byte[0];

    private final Path snapshotRoot;
    private final Timeline timeline;
    private final EngineSettings settings;

    public Checkpoint(Path snapshotRoot, Timeline timeline, EngineSettings settings) {
        if (snapshotRoot == null || timeline == null || settings == null) {
            throw new IllegalArgumentException("null checkpoint input");
        }
        this.snapshotRoot = snapshotRoot;
        this.timeline = timeline;
        this.settings = settings;
    }

    /** The settings this checkpoint was built with. */
    public EngineSettings settings() {
        return settings;
    }

    /** 建点请求：一次建点内每个 (路径, 子目标) 最多一条。 */
    public static final class ChangeSet {
        private final List<Entry> entries = new ArrayList<>();

        public ChangeSet put(String relPath, long subTarget, byte[] data) {
            return put(relPath, subTarget, subTarget == 0L ? Block.TYPE_FILE : Block.TYPE_CHUNK, data);
        }

        public ChangeSet put(String relPath, long subTarget, int type, byte[] data) {
            if (data == null) {
                throw new IllegalArgumentException("null data for " + relPath);
            }
            entries.add(new Entry(relPath, subTarget, type, data.clone(), false, false));
            return this;
        }

        /**
         * 新建目标：目标在本次建点之前不存在。若该子目标没有前一块，先写一个 ABSENT
         * 标记块（描述首次跟踪写之前的状态），再写内容块；这样更早的快照解析为 ABSENT。
         */
        public ChangeSet putNew(String relPath, long subTarget, byte[] data) {
            return putNew(relPath, subTarget, subTarget == 0L ? Block.TYPE_FILE : Block.TYPE_CHUNK, data);
        }

        public ChangeSet putNew(String relPath, long subTarget, int type, byte[] data) {
            if (data == null) {
                throw new IllegalArgumentException("null data for " + relPath);
            }
            entries.add(new Entry(relPath, subTarget, type, data.clone(), false, true));
            return this;
        }

        public ChangeSet absent(String relPath, long subTarget) {
            return absent(relPath, subTarget, subTarget == 0L ? Block.TYPE_FILE : Block.TYPE_CHUNK);
        }

        public ChangeSet absent(String relPath, long subTarget, int type) {
            entries.add(new Entry(relPath, subTarget, type, null, true, false));
            return this;
        }

        public List<Entry> entries() {
            return Collections.unmodifiableList(entries);
        }

        /** 一条变更。 */
        public static final class Entry {
            private final String relPath;
            private final long subTarget;
            private final int type;
            private final byte[] data;
            private final boolean absent;
            private final boolean newTarget;

            private Entry(String relPath, long subTarget, int type, byte[] data, boolean absent,
                          boolean newTarget) {
                this.relPath = relPath;
                this.subTarget = subTarget;
                this.type = type;
                this.data = data;
                this.absent = absent;
                this.newTarget = newTarget;
            }

            public String relPath() {
                return relPath;
            }

            public long subTarget() {
                return subTarget;
            }

            public int type() {
                return type;
            }

            public byte[] data() {
                return data;
            }

            public boolean absent() {
                return absent;
            }

            public boolean newTarget() {
                return newTarget;
            }
        }
    }

    /** 快照元数据；未设置的字段留空。 */
    public static final class SnapshotInfo {
        private String displayName = "";
        private String worldName = "";
        private String minecraftVersion = "";
        private String engineVersion = "";
        private String source = "";
        private String createdBy = "";
        private boolean damaged;
        private long extraLogicalBytes;
        private long extraPhysicalBytes;

        public SnapshotInfo displayName(String value) {
            this.displayName = value;
            return this;
        }

        public SnapshotInfo worldName(String value) {
            this.worldName = value;
            return this;
        }

        public SnapshotInfo minecraftVersion(String value) {
            this.minecraftVersion = value;
            return this;
        }

        public SnapshotInfo engineVersion(String value) {
            this.engineVersion = value;
            return this;
        }

        public SnapshotInfo createdBy(String value) {
            this.createdBy = value;
            return this;
        }

        public SnapshotInfo source(String value) {
            this.source = value;
            return this;
        }

        public SnapshotInfo damaged(boolean value) {
            this.damaged = value;
            return this;
        }

        /** 附带的非 NBT 文件（JSON）大小，计入快照的逻辑/物理字节数。 */
        public SnapshotInfo extraBytes(long logical, long physical) {
            this.extraLogicalBytes = logical;
            this.extraPhysicalBytes = physical;
            return this;
        }
    }

    /** 写入一次建点；返回已提交的快照节点。 */
    public Snapshot create(ChangeSet changes, SnapshotInfo info) throws IOException {
        if (changes == null) {
            throw new IllegalArgumentException("null change set");
        }
        SnapshotInfo metadata = info == null ? new SnapshotInfo() : info;
        Files.createDirectories(snapshotRoot);
        int generation = timeline.allocateGen();
        String slot = "S" + generation;
        while (timeline.snapshot(slot) != null) {
            generation = timeline.allocateGen();
            slot = "S" + generation;
        }
        long now = System.currentTimeMillis();
        Set<Integer> visible = timeline.liveGenerations();

        Map<String, List<ChangeSet.Entry>> groups = new LinkedHashMap<>();
        for (ChangeSet.Entry entry : changes.entries()) {
            String key;
            try {
                key = VaultPaths.normalizeKey(entry.relPath());
            } catch (IllegalArgumentException e) {
                throw new IOException("invalid key: " + entry.relPath(), e);
            }
            if (VaultPaths.isExcluded(key)) {
                continue;
            }
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry);
        }

        int entryCount = 0;
        int skipped = 0;
        long logicalBytes = 0L;
        long physicalBytes = 0L;
        Map<String, Long> oldCache = new HashMap<>();
        List<String> touched = new ArrayList<>();
        try {
            for (Map.Entry<String, List<ChangeSet.Entry>> group : groups.entrySet()) {
                String key = group.getKey();
                List<ChangeSet.Entry> entries = group.getValue();
                Map<Long, Integer> perTarget = new HashMap<>();
                for (ChangeSet.Entry entry : entries) {
                    int count = perTarget.merge(entry.subTarget(), 1, Integer::sum);
                    if (count > 2) {
                        throw new IllegalArgumentException("more than baseline+latest for sub-target "
                                + entry.subTarget() + " in " + key);
                    }
                }
                Path file = VaultPaths.zpackFor(snapshotRoot, key);
                Long cachedValue = timeline.cacheOffset(key);
                long cacheOffset = cachedValue == null ? -1L : cachedValue;
                oldCache.put(key, cacheOffset);
                touched.add(key);
                BlockTable table = Files.exists(file)
                        ? BlockFile.loadTable(file, cacheOffset)
                        : BlockTable.empty();
                List<BlockFile.NewBlock> newBlocks = new ArrayList<>(entries.size());
                int nextNumber = table.nextBlockNumber();
                Map<Long, Block> pendingLast = new HashMap<>();
                Map<Long, byte[]> pendingRaw = new HashMap<>();
                Map<Long, Integer> pendingVersions = new HashMap<>();
                for (ChangeSet.Entry entry : entries) {
                    Block previous = pendingLast.containsKey(entry.subTarget())
                            ? pendingLast.get(entry.subTarget())
                            : table.lastFor(entry.subTarget());
                    if (previous == null && entry.newTarget() && !entry.absent()) {
                        Block marker = Block.create(nextNumber, 0, generation, entry.subTarget(),
                                entry.type(), Block.MODE_ABSENT, 0, 0L, 0);
                        newBlocks.add(new BlockFile.NewBlock(marker, EMPTY));
                        nextNumber++;
                        previous = marker;
                    }
                    int mode;
                    int dictionary = 0;
                    byte[] payload;
                    int originalLength;
                    long hash;
                    int versionsSinceFull;
                    byte[] stored = null;
                    boolean pending = previous != null
                            && pendingLast.get(entry.subTarget()) == previous;
                    if (entry.absent()) {
                        // A repeated deletion is also a no-op when the previous ABSENT block is
                        // visible (or pending in this batch). An ABSENT block from an abandoned
                        // branch must not hide a target that is present in the new branch.
                        if (previous != null && previous.mode() == Block.MODE_ABSENT
                                && (pending || visible.contains(previous.generation()))) {
                            skipped++;
                            continue;
                        }
                        mode = Block.MODE_ABSENT;
                        payload = EMPTY;
                        originalLength = 0;
                        hash = 0L;
                        versionsSinceFull = 0;
                    } else {
                        int level = settings.zstdLevel();
                        // The base is the previous stored block, i.e. already canonicalised and
                        // aligned bytes (PLAN 1 §2). Reconstructing it is also what the delta path
                        // needs, so keyframe FULL blocks reuse the same read.
                        byte[] base = null;
                        if (previous != null && previous.mode() != Block.MODE_ABSENT) {
                            base = pendingRaw.containsKey(entry.subTarget())
                                    ? pendingRaw.get(entry.subTarget())
                                    : BlockFile.reconstruct(file, table, previous);
                        }
                        NbtCanon.Result canonical = NbtCanon.canonicalize(entry.data());
                        byte[] raw = canonical.bytes();
                        if (base != null) {
                            raw = NbtCanon.alignCanonical(base, canonical);
                        }
                        stored = raw;
                        originalLength = raw.length;
                        hash = Xxh64.hash(raw);
                        // P0: a captured write that produces the same stored bytes as the
                        // previous block is a no-op. Do not append a block (and do not consume a
                        // block number); the previous block remains the target's head, so the
                        // new snapshot resolves to the same value. This is safe for both the
                        // baseline entry (old value) and the latest entry (current value).
                        if (previous != null
                                && previous.mode() != Block.MODE_ABSENT
                                && previous.subTargetType() == entry.type()
                                && (pending || visible.contains(previous.generation()))
                                && hash == previous.hash()) {
                            skipped++;
                            continue;
                        }
                        if (previous == null || previous.mode() == Block.MODE_ABSENT) {
                            mode = Block.MODE_FULL;
                            payload = Codec.compressFull(raw, level, entry.type());
                            versionsSinceFull = 0;
                        } else {
                            // Count versions of this sub-target, not file-wide block numbers: one
                            // bulk save appends dozens of blocks at once, and the old difference
                            // then forced every target to a FULL block instead of a delta.
                            int interval = settings.keyframeInterval();
                            int sinceFull = pendingVersions.containsKey(entry.subTarget())
                                    ? pendingVersions.get(entry.subTarget())
                                    : deltaChainLength(table, previous);
                            if (interval > 0 && sinceFull + 1 >= interval) {
                                mode = Block.MODE_FULL;
                                payload = Codec.compressFull(raw, level, entry.type());
                                versionsSinceFull = 0;
                            } else {
                                byte[] delta = Codec.compressDelta(raw, base, settings, entry.type());
                                mode = Block.MODE_DELTA;
                                payload = delta;
                                dictionary = previous.number();
                                versionsSinceFull = sinceFull + 1;
                            }
                        }
                    }
                    int number = nextNumber++;
                    Block block = Block.create(number, dictionary, generation, entry.subTarget(),
                            entry.type(), mode, originalLength, hash, payload.length);
                    newBlocks.add(new BlockFile.NewBlock(block, payload));
                    pendingLast.put(entry.subTarget(), block);
                    // The next block in this batch must diff against the stored (canonical,
                    // aligned) bytes, not the captured bytes.
                    pendingRaw.put(entry.subTarget(), stored);
                    pendingVersions.put(entry.subTarget(), versionsSinceFull);
                    entryCount++;
                    logicalBytes += originalLength;
                    physicalBytes += payload.length;
                }
                long newCache = BlockFile.append(file, table, newBlocks, cacheOffset,
                        settings.verifyAfterWrite());
                timeline.putCacheOffset(key, newCache);
            }
            if (skipped > 0) {
                Log.LOGGER.debug("checkpoint {}: skipped {} no-op block(s)", slot, skipped);
            }

            Snapshot snapshot = new Snapshot.Builder(slot)
                    .generation(generation)
                    .parentSlot(timeline.head())
                    .savedAt(now)
                    .damaged(metadata.damaged)
                    .tombstone(false)
                    .displayName(metadata.displayName)
                    .worldName(metadata.worldName)
                    .minecraftVersion(metadata.minecraftVersion)
                    .engineVersion(metadata.engineVersion)
                    .source(metadata.source)
                    .createdBy(metadata.createdBy)
                    .entryCount(entryCount)
                    .logicalBytes(logicalBytes + metadata.extraLogicalBytes)
                    .physicalBytes(physicalBytes + metadata.extraPhysicalBytes)
                    .build();
            String previousHead = timeline.head();
            timeline.add(snapshot);
            timeline.setHead(slot);
            try {
                timeline.save(snapshotRoot.resolve(VaultPaths.INDEX_FILE_NAME));
            } catch (IOException e) {
                timeline.remove(slot);
                timeline.setHead(previousHead);
                throw e;
            }
            return snapshot;
        } catch (IOException | RuntimeException e) {
            for (String key : touched) {
                Long old = oldCache.get(key);
                if (old == null || old < 0L) {
                    timeline.removeCacheOffset(key);
                } else {
                    timeline.putCacheOffset(key, old);
                }
            }
            throw e;
        }
    }

    /** 该子目标自上一个 FULL 以来的 delta 数；链断裂时按已走过的跳数计。 */
    private static int deltaChainLength(BlockTable table, Block from) {
        Block current = from;
        int length = 0;
        while (current != null && current.mode() == Block.MODE_DELTA && length < 100_000) {
            Block previous = table.byNumber(current.dictionary());
            if (previous == null) {
                return length;
            }
            current = previous;
            length++;
        }
        return length;
    }
}
