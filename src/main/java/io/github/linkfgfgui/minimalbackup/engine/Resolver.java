package io.github.linkfgfgui.minimalbackup.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * 回滚读取：给定逻辑键、子目标与目标快照，返回字节 / ABSENT / PASSTHROUGH。
 *
 * <p>解析规则：取目标快照祖先链代次集合里编号最大的块；若可见代次一块都没有，
 * 取该子目标第 1 块（它描述首次跟踪写之前的状态，对更早的快照同样成立）。
 */
public final class Resolver {
    /** 解析结果种类。 */
    public enum Kind {
        /** 命中备份，{@link Result#data()} 为完整字节。 */
        PRESENT,
        /** 快照时该子目标不存在。 */
        ABSENT,
        /** 该子目标没有任何块，放行磁盘。 */
        PASSTHROUGH
    }

    /** 解析结果。 */
    public static final class Result {
        private static final Result ABSENT = new Result(Kind.ABSENT, null);
        private static final Result PASSTHROUGH = new Result(Kind.PASSTHROUGH, null);

        private final Kind kind;
        private final byte[] data;

        private Result(Kind kind, byte[] data) {
            this.kind = kind;
            this.data = data;
        }

        public static Result present(byte[] data) {
            return new Result(Kind.PRESENT, data);
        }

        public static Result absent() {
            return ABSENT;
        }

        public static Result passthrough() {
            return PASSTHROUGH;
        }

        public Kind kind() {
            return kind;
        }

        public byte[] data() {
            return data;
        }
    }

    private final Path snapshotRoot;
    private final Timeline timeline;

    public Resolver(Path snapshotRoot, Timeline timeline) {
        if (snapshotRoot == null || timeline == null) {
            throw new IllegalArgumentException("null resolver input");
        }
        this.snapshotRoot = snapshotRoot;
        this.timeline = timeline;
    }

    /** 解析一个子目标（不区分类型；subTarget 0 上同时存在整文件和区块块时应使用 typed 重载）。 */
    public Result resolve(String relPath, long subTarget, String slot) throws IOException {
        return resolve(relPath, null, subTarget, -1, slot);
    }

    /** 只解析指定类型的块。 */
    public Result resolve(String relPath, long subTarget, int subTargetType, String slot)
            throws IOException {
        return resolve(relPath, null, subTarget, subTargetType, slot);
    }

    /** 类型感知解析，使用调用方已加载的块表。 */
    public Result resolve(String relPath, BlockTable table, long subTarget, int subTargetType,
                          String slot) throws IOException {
        if (subTargetType != -1
                && subTargetType != Block.TYPE_FILE
                && subTargetType != Block.TYPE_CHUNK) {
            throw new IllegalArgumentException("bad sub-target type: " + subTargetType);
        }
        String key;
        try {
            key = VaultPaths.normalizeKey(relPath);
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid key: " + relPath, e);
        }
        if (VaultPaths.isExcluded(key)) {
            return Result.passthrough();
        }
        Path file = VaultPaths.zpackFor(snapshotRoot, key);
        if (!Files.exists(file)) {
            return Result.passthrough();
        }
        Snapshot target = timeline.snapshot(slot);
        if (target == null) {
            throw new IOException("unknown snapshot: " + slot);
        }
        BlockTable current = table;
        if (current == null) {
            Long cached = timeline.cacheOffset(key);
            current = BlockFile.loadTable(file, cached == null ? -1L : cached);
        }
        List<Block> blocks = subTargetType < 0
                ? current.blocksFor(subTarget)
                : current.blocksFor(subTarget, subTargetType);
        if (blocks.isEmpty()) {
            return Result.passthrough();
        }
        Set<Integer> visible = timeline.visibleGenerations(slot);
        Block chosen = null;
        for (Block block : blocks) {
            if (visible.contains(block.generation())
                    && (chosen == null || block.number() > chosen.number())) {
                chosen = block;
            }
        }
        if (chosen == null) {
            chosen = blocks.get(0);
        }
        if (chosen.mode() == Block.MODE_ABSENT) {
            return Result.absent();
        }
        return Result.present(BlockFile.reconstruct(file, current, chosen));
    }
}
