package io.github.linkfgfgui.minimalbackup.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 启动自愈：清临时文件、截断半截块/块号断裂、统计孤儿块。
 *
 * <p>索引读不动由 {@link Timeline#load(Path)} 抛错，要求人工介入，本类不重建。
 */
public final class SelfHealing {
    /** 一次自愈的统计。 */
    public static final class Report {
        private int tempFilesDeleted;
        private int filesTruncated;
        private long bytesTruncated;
        private long orphanBlocks;
        private final List<String> damagedFiles = new ArrayList<>();

        public int tempFilesDeleted() {
            return tempFilesDeleted;
        }

        public int filesTruncated() {
            return filesTruncated;
        }

        public long bytesTruncated() {
            return bytesTruncated;
        }

        public long orphanBlocks() {
            return orphanBlocks;
        }

        public List<String> damagedFiles() {
            return new ArrayList<>(damagedFiles);
        }
    }

    /** 扫描并修复整个快照根。 */
    public Report heal(Path snapshotRoot, Timeline timeline) throws IOException {
        Report report = new Report();
        if (snapshotRoot == null || !Files.isDirectory(snapshotRoot)) {
            return report;
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(snapshotRoot)) {
            walk.filter(Files::isRegularFile).sorted().forEach(files::add);
        }
        for (Path file : files) {
            String name = file.getFileName().toString();
            if (name.endsWith(VaultPaths.COMPACT_TEMP_SUFFIX) || name.endsWith(AtomicFiles.TEMP_SUFFIX)) {
                if (Files.deleteIfExists(file)) {
                    report.tempFilesDeleted++;
                }
            }
        }
        Set<Integer> knownGenerations = new HashSet<>();
        for (Snapshot snapshot : timeline.snapshots()) {
            knownGenerations.add(snapshot.generation());
        }
        for (Path file : files) {
            if (!Files.exists(file) || !file.getFileName().toString().endsWith(VaultPaths.ZPACK_SUFFIX)) {
                continue;
            }
            long size = Files.size(file);
            if (size < BlockFile.FILE_HEADER_SIZE) {
                report.damagedFiles.add(file.toString());
                continue;
            }
            BlockFile.Scan scan;
            try {
                scan = BlockFile.scan(file);
            } catch (IOException e) {
                report.damagedFiles.add(file.toString());
                continue;
            }
            long blockAreaEnd = scan.blockAreaEnd();
            if (scan.fileLength() > blockAreaEnd) {
                long cacheLength = BlockFile.cacheLength(file, blockAreaEnd);
                if (cacheLength >= 0) {
                    long cacheEnd = blockAreaEnd + cacheLength;
                    if (cacheEnd < scan.fileLength()) {
                        BlockFile.truncate(file, cacheEnd);
                        report.filesTruncated++;
                        report.bytesTruncated += scan.fileLength() - cacheEnd;
                    }
                } else {
                    BlockFile.truncate(file, blockAreaEnd);
                    report.filesTruncated++;
                    report.bytesTruncated += scan.fileLength() - blockAreaEnd;
                }
            }
            for (Block block : scan.blocks()) {
                if (!knownGenerations.contains(block.generation())) {
                    report.orphanBlocks++;
                }
            }
        }
        VaultPaths.deleteEmptyDirs(snapshotRoot);
        return report;
    }
}
