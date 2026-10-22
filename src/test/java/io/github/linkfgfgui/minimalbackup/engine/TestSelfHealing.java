package io.github.linkfgfgui.minimalbackup.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/** E9: 临时文件清理、半截块/块号断裂截断、孤儿统计、索引损坏明确报错。 */
final class TestSelfHealing {
    private TestSelfHealing() {
    }

    static void run() throws Exception {
        Path dir = TestSupport.tempDir("selfheal");
        try {
            clean(dir);
            repair(dir);
            indexFailure(dir);
        } finally {
            TestSupport.deleteTree(dir);
        }
    }

    private static void clean(Path dir) throws IOException {
        Path snapshotRoot = dir.resolve("clean/minimalbackup_snapshots");
        Timeline timeline = Timeline.empty();
        Checkpoint checkpoint = new Checkpoint(snapshotRoot, timeline, TestSupport.SETTINGS);
        checkpoint.create(new Checkpoint.ChangeSet().put("level.dat", 0L, TestSupport.bytes(512, 70)),
                new Checkpoint.SnapshotInfo().source("test"));
        SelfHealing.Report report = new SelfHealing().heal(snapshotRoot, timeline);
        TestSupport.eq(0, report.tempFilesDeleted(), "clean temp");
        TestSupport.eq(0, report.filesTruncated(), "clean truncate");
        TestSupport.eq(0L, report.orphanBlocks(), "clean orphan");
        TestSupport.eq(0, report.damagedFiles().size(), "clean damaged");
    }

    private static void repair(Path dir) throws IOException {
        Path snapshotRoot = dir.resolve("repair/minimalbackup_snapshots");
        Timeline timeline = Timeline.empty();
        Checkpoint checkpoint = new Checkpoint(snapshotRoot, timeline, TestSupport.SETTINGS);
        byte[] original = TestSupport.bytes(1024, 71);
        checkpoint.create(new Checkpoint.ChangeSet().put("data.dat", 0L, original),
                new Checkpoint.SnapshotInfo().source("test"));
        Path file = VaultPaths.zpackFor(snapshotRoot, "data.dat");
        Long cache = timeline.cacheOffset("data.dat");
        BlockTable table = BlockFile.loadTable(file, cache);
        long blockEnd = table.blockAreaEnd();

        TestSupport.write(snapshotRoot.resolve("stray.tmp"), TestSupport.text("x"));
        TestSupport.write(snapshotRoot.resolve("sub/stray.mb-tmp"), TestSupport.text("y"));
        TestSupport.write(snapshotRoot.resolve("sub/deep/other.tmp"), TestSupport.text("z"));

        BlockFile.truncate(file, blockEnd);
        byte[] halfHeader = TestSupport.fullBlock(2, 2, 0L, TestSupport.bytes(1024, 72)).block().encodeHeader();
        Files.write(file, halfHeader, StandardOpenOption.APPEND);
        SelfHealing.Report half = new SelfHealing().heal(snapshotRoot, timeline);
        TestSupport.eq(3, half.tempFilesDeleted(), "temp files deleted");
        TestSupport.eq(1, half.filesTruncated(), "half block truncated");
        TestSupport.eq(Block.HEADER_SIZE, half.bytesTruncated(), "half block bytes");
        TestSupport.eq(blockEnd, Files.size(file), "half block removed");

        BlockFile.truncate(file, blockEnd);
        BlockFile.NewBlock gapBlock = TestSupport.fullBlock(5, 2, 0L, TestSupport.bytes(512, 73));
        Files.write(file, gapBlock.block().encodeHeader(), StandardOpenOption.APPEND);
        Files.write(file, gapBlock.payload(), StandardOpenOption.APPEND);
        SelfHealing.Report gap = new SelfHealing().heal(snapshotRoot, timeline);
        TestSupport.eq(1, gap.filesTruncated(), "number gap truncated");
        TestSupport.eq(blockEnd, Files.size(file), "number gap removed");

        BlockTable reloaded = BlockFile.loadTable(file, -1L);
        byte[] orphan = TestSupport.bytes(1024, 74);
        BlockFile.append(file, reloaded,
                List.of(TestSupport.fullBlock(reloaded.nextBlockNumber(), 99, 0L, orphan)), -1L, true);
        SelfHealing.Report orphanReport = new SelfHealing().heal(snapshotRoot, timeline);
        TestSupport.eq(1L, orphanReport.orphanBlocks(), "orphan counted");
        TestSupport.eq(0, orphanReport.filesTruncated(), "valid cache not truncated");

        BlockTable beforeGarbage = BlockFile.loadTable(file, -1L);
        long cacheStart = beforeGarbage.blockAreaEnd();
        Files.write(file, new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, StandardOpenOption.APPEND);
        SelfHealing.Report trailing = new SelfHealing().heal(snapshotRoot, timeline);
        TestSupport.eq(1, trailing.filesTruncated(), "trailing garbage truncated");
        TestSupport.eq(8L, trailing.bytesTruncated(), "only trailing garbage removed");
        TestSupport.check(BlockFile.cacheLength(file, cacheStart) > 0, "valid cache preserved");

        Path damaged = VaultPaths.zpackFor(snapshotRoot, "bad.zpack");
        TestSupport.write(damaged, new byte[]{1, 2, 3, 4});
        SelfHealing.Report damagedReport = new SelfHealing().heal(snapshotRoot, timeline);
        TestSupport.eq(1, damagedReport.damagedFiles().size(), "damaged file reported");

        Resolver resolver = new Resolver(snapshotRoot, timeline);
        Resolver.Result result = resolver.resolve("data.dat", 0L, timeline.head());
        TestSupport.eq(Resolver.Kind.PRESENT, result.kind(), "committed data still resolves");
        TestSupport.bytesEq(original, result.data(), "committed data intact");

        new Compactor(snapshotRoot, timeline).compact();
        Resolver.Result after = new Resolver(snapshotRoot, timeline).resolve("data.dat", 0L, timeline.head());
        TestSupport.bytesEq(original, after.data(), "data intact after compaction");
    }

    private static void indexFailure(Path dir) throws IOException {
        Path snapshotRoot = dir.resolve("bad-index/minimalbackup_snapshots");
        Files.createDirectories(snapshotRoot);
        Path index = snapshotRoot.resolve(VaultPaths.INDEX_FILE_NAME);
        Files.write(index, ("format=1\nslot.S0.gen=" + (char) 92 + "uZZZZ\n")
                .getBytes(StandardCharsets.UTF_8));
        TestSupport.expectIOException(() -> Timeline.load(index), "corrupt index rejected");
    }
}
