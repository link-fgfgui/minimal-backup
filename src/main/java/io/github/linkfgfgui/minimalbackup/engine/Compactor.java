package io.github.linkfgfgui.minimalbackup.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 按文件压实 + 孤儿块清理。
 *
 * <p>mark = head 祖先链上所有快照可见的块（每个活代次自己的块），加上"最老活快照早于
 * 首块"时需要保留的首块，再沿字典链闭包；其余为 sweep（孤儿块、死分支、过期缓存区）。
 * 存活块按原顺序重编号、重指字典，写临时文件后原子改名。
 */
public final class Compactor {
    private final Path snapshotRoot;
    private final Timeline timeline;

    public Compactor(Path snapshotRoot, Timeline timeline) {
        if (snapshotRoot == null || timeline == null) {
            throw new IllegalArgumentException("null compactor input");
        }
        this.snapshotRoot = snapshotRoot;
        this.timeline = timeline;
    }

    /** 一次压实的统计。 */
    public static final class Report {
        private int filesScanned;
        private int filesSkipped;
        private int filesCompacted;
        private int filesDeleted;
        private long blocksScanned;
        private long blocksKept;
        private long blocksDropped;

        public int filesScanned() {
            return filesScanned;
        }

        public int filesSkipped() {
            return filesSkipped;
        }

        public int filesCompacted() {
            return filesCompacted;
        }

        public int filesDeleted() {
            return filesDeleted;
        }

        public long blocksScanned() {
            return blocksScanned;
        }

        public long blocksKept() {
            return blocksKept;
        }

        public long blocksDropped() {
            return blocksDropped;
        }
    }

    /** 压实整个快照根；必要时更新并保存索引里的缓存指针。 */
    public Report compact() throws IOException {
        Set<Integer> live = timeline.liveGenerations();
        int oldestLive = live.isEmpty() ? Integer.MAX_VALUE : Collections.min(live);
        Report report = new Report();
        List<Path> files = VaultPaths.listZpacks(snapshotRoot);
        Set<String> existing = new HashSet<>();
        for (Path file : files) {
            String key = VaultPaths.keyForZpack(snapshotRoot, file);
            existing.add(key);
            report.filesScanned++;
            Long cachedValue = timeline.cacheOffset(key);
            long cacheOffset = cachedValue == null ? -1L : cachedValue;
            BlockTable table;
            try {
                table = BlockFile.loadTable(file, cacheOffset);
            } catch (IOException e) {
                Log.LOGGER.warn("compactor: unreadable {} ({})", file, e.toString());
                report.filesSkipped++;
                continue;
            }
            report.blocksScanned += table.blocks().size();
            Set<Integer> mark = mark(table, live, oldestLive);
            if (mark.isEmpty()) {
                Files.delete(file);
                timeline.removeCacheOffset(key);
                report.filesDeleted++;
                continue;
            }
            boolean cacheValid = cacheOffset >= 0
                    && cacheOffset == table.blockAreaEnd()
                    && BlockFile.readCache(file, cacheOffset) != null;
            if (mark.size() == table.blocks().size() && cacheValid) {
                report.filesSkipped++;
                continue;
            }
            List<Block> liveBlocks = new ArrayList<>(mark.size());
            for (Block block : table.blocks()) {
                if (mark.contains(block.number())) {
                    liveBlocks.add(block);
                }
            }
            long newCache = BlockFile.compact(file, liveBlocks);
            timeline.putCacheOffset(key, newCache);
            report.filesCompacted++;
            report.blocksKept += liveBlocks.size();
            report.blocksDropped += table.blocks().size() - liveBlocks.size();
        }

        List<String> stale = new ArrayList<>();
        for (String key : timeline.cacheKeys()) {
            if (!existing.contains(key)) {
                stale.add(key);
            }
        }
        for (String key : stale) {
            timeline.removeCacheOffset(key);
        }
        if (report.filesCompacted > 0 || report.filesDeleted > 0 || !stale.isEmpty()) {
            timeline.save(snapshotRoot.resolve(VaultPaths.INDEX_FILE_NAME));
        }
        return report;
    }

    private static Set<Integer> mark(BlockTable table, Set<Integer> live, int oldestLive) {
        Set<Integer> mark = new HashSet<>();
        for (Block block : table.blocks()) {
            if (live.contains(block.generation())) {
                mark.add(block.number());
            }
        }
        if (!live.isEmpty()) {
            for (long subTarget : table.subTargets()) {
                List<Block> blocks = table.blocksFor(subTarget);
                boolean oldestVisible = false;
                for (Block block : blocks) {
                    if (block.generation() == oldestLive) {
                        oldestVisible = true;
                        break;
                    }
                }
                if (!blocks.isEmpty() && !oldestVisible) {
                    mark.add(blocks.get(0).number());
                }
            }
        }
        Deque<Integer> queue = new ArrayDeque<>(mark);
        while (!queue.isEmpty()) {
            Block block = table.byNumber(queue.poll());
            if (block == null) {
                continue;
            }
            if (block.dictionary() != 0 && mark.add(block.dictionary())) {
                queue.add(block.dictionary());
            }
        }
        return mark;
    }
}
