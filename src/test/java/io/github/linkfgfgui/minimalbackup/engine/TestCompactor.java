package io.github.linkfgfgui.minimalbackup.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** E8: 孤儿/死分支清理、字典链保持、压实后解析不变、首块保留规则。 */
final class TestCompactor {
    private TestCompactor() {
    }

    static void run() throws Exception {
        Path dir = TestSupport.tempDir("compactor");
        try {
            branch(dir);
            oldestRule(dir);
            olderThanFirstBlock(dir);
        } finally {
            TestSupport.deleteTree(dir);
        }
    }

    private static void branch(Path dir) throws IOException {
        Path snapshotRoot = dir.resolve("branch/minimalbackup_snapshots");
        Timeline timeline = Timeline.empty();
        timeline.add(TestSupport.snap("S1", 1, ""));
        timeline.add(TestSupport.snap("S2", 2, "S1"));
        timeline.add(TestSupport.snap("S3", 3, "S2"));
        timeline.add(TestSupport.snap("S4", 4, "S1"));
        timeline.setHead("S4");
        timeline.save(snapshotRoot.resolve(VaultPaths.INDEX_FILE_NAME));

        byte[] v0 = TestSupport.bytes(2048, 60);
        byte[] v1 = TestSupport.mutate(v0, 61);
        byte[] v2 = TestSupport.mutate(v1, 62);
        byte[] v3 = TestSupport.mutate(v2, 63);
        Path file = VaultPaths.zpackFor(snapshotRoot, "data.dat");
        BlockTable table = BlockTable.empty();
        long cache = -1L;
        cache = BlockFile.append(file, table, List.of(TestSupport.fullBlock(1, 1, 0L, v0)), cache, true);
        cache = BlockFile.append(file, table, List.of(TestSupport.deltaBlock(2, 1, 2, 0L, v1, v0)), cache, true);
        cache = BlockFile.append(file, table, List.of(TestSupport.deltaBlock(3, 2, 3, 0L, v2, v1)), cache, true);
        cache = BlockFile.append(file, table, List.of(TestSupport.fullBlock(4, 4, 0L, v3)), cache, true);
        timeline.putCacheOffset("data.dat", cache);
        timeline.save(snapshotRoot.resolve(VaultPaths.INDEX_FILE_NAME));

        Resolver resolver = new Resolver(snapshotRoot, timeline);
        present(resolver, "data.dat", "S1", v0, "before compact S1");
        present(resolver, "data.dat", "S4", v3, "before compact S4");

        Compactor.Report report = new Compactor(snapshotRoot, timeline).compact();
        TestSupport.eq(1, report.filesCompacted(), "compacted files");
        TestSupport.eq(2L, report.blocksDropped(), "dropped dead blocks");
        TestSupport.eq(2L, report.blocksKept(), "kept live blocks");

        BlockTable compacted = BlockFile.loadTable(file, timeline.cacheOffset("data.dat"));
        TestSupport.eq(2, compacted.blocks().size(), "blocks after compact");
        TestSupport.eq(1, compacted.byNumber(1).generation(), "renumbered block1 gen");
        TestSupport.eq(4, compacted.byNumber(2).generation(), "renumbered block2 gen");
        TestSupport.eq(0, compacted.byNumber(2).dictionary(), "renumbered block2 dictionary");
        present(resolver, "data.dat", "S1", v0, "after compact S1");
        present(resolver, "data.dat", "S4", v3, "after compact S4");

        Timeline reloaded = Timeline.load(snapshotRoot.resolve(VaultPaths.INDEX_FILE_NAME));
        TestSupport.eq(timeline.cacheOffset("data.dat").longValue(),
                reloaded.cacheOffset("data.dat").longValue(), "cache pointer persisted");
        present(new Resolver(snapshotRoot, reloaded), "data.dat", "S4", v3, "reloaded resolution");

        Long afterCache = timeline.cacheOffset("data.dat");
        BlockTable after = BlockFile.loadTable(file, afterCache);
        byte[] orphan = TestSupport.bytes(2048, 64);
        BlockFile.append(file, after, List.of(TestSupport.fullBlock(after.nextBlockNumber(), 99, 0L, orphan)),
                afterCache, true);
        Compactor.Report orphanReport = new Compactor(snapshotRoot, timeline).compact();
        TestSupport.eq(1L, orphanReport.blocksDropped(), "orphan dropped");
        TestSupport.eq(2, BlockFile.loadTable(file, timeline.cacheOffset("data.dat")).blocks().size(),
                "orphan removed");
        present(resolver, "data.dat", "S4", v3, "orphan does not change resolution");

        Path dead = VaultPaths.zpackFor(snapshotRoot, "dead.dat");
        BlockTable deadTable = BlockTable.empty();
        long deadCache = BlockFile.append(dead, deadTable,
                List.of(TestSupport.fullBlock(1, 2, 0L, TestSupport.text("dead value"))), -1L, true);
        timeline.putCacheOffset("dead.dat", deadCache);
        Compactor.Report keepReport = new Compactor(snapshotRoot, timeline).compact();
        TestSupport.eq(0, keepReport.filesDeleted(), "first block kept for older live snapshot");
        present(resolver, "dead.dat", "S1", TestSupport.text("dead value"), "first block fallback after compact");

        timeline.setHead("");
        Compactor.Report wipe = new Compactor(snapshotRoot, timeline).compact();
        TestSupport.check(wipe.filesDeleted() >= 2, "all files deleted without live head");
        TestSupport.check(!Files.exists(file), "data file deleted");
        TestSupport.check(!Files.exists(dead), "dead file deleted");
        TestSupport.check(Timeline.load(snapshotRoot.resolve(VaultPaths.INDEX_FILE_NAME))
                .cacheKeys().isEmpty(), "stale cache pointers removed");
    }

    private static void oldestRule(Path dir) throws IOException {
        Path snapshotRoot = dir.resolve("oldest/minimalbackup_snapshots");
        Timeline timeline = Timeline.empty();
        timeline.add(TestSupport.snap("S2", 2, ""));
        timeline.add(TestSupport.snap("S5", 5, "S2"));
        timeline.setHead("S5");
        timeline.save(snapshotRoot.resolve(VaultPaths.INDEX_FILE_NAME));

        byte[] old = TestSupport.text("old dead first block");
        byte[] live = TestSupport.text("live root value");
        Path file = VaultPaths.zpackFor(snapshotRoot, "x.dat");
        BlockTable table = BlockTable.empty();
        long cache = -1L;
        cache = BlockFile.append(file, table, List.of(TestSupport.fullBlock(1, 1, 0L, old)), cache, true);
        cache = BlockFile.append(file, table, List.of(TestSupport.fullBlock(2, 2, 0L, live)), cache, true);
        timeline.putCacheOffset("x.dat", cache);

        Compactor.Report report = new Compactor(snapshotRoot, timeline).compact();
        TestSupport.eq(1L, report.blocksDropped(), "dead pre-root block dropped");
        BlockTable compacted = BlockFile.loadTable(file, timeline.cacheOffset("x.dat"));
        TestSupport.eq(1, compacted.blocks().size(), "only live root block remains");
        TestSupport.eq(2, compacted.byNumber(1).generation(), "root block renumbered");
        Resolver resolver = new Resolver(snapshotRoot, timeline);
        present(resolver, "x.dat", "S2", live, "S2 after compact");
        present(resolver, "x.dat", "S5", live, "S5 after compact");
    }

    private static void olderThanFirstBlock(Path dir) throws IOException {
        Path snapshotRoot = dir.resolve("pre-root/minimalbackup_snapshots");
        Timeline timeline = Timeline.empty();
        timeline.add(TestSupport.snap("S2", 2, ""));
        timeline.add(TestSupport.snap("S5", 5, "S2"));
        timeline.setHead("S5");
        timeline.save(snapshotRoot.resolve(VaultPaths.INDEX_FILE_NAME));

        byte[] value = TestSupport.text("first block older than live root");
        Path file = VaultPaths.zpackFor(snapshotRoot, "pre.dat");
        BlockTable table = BlockTable.empty();
        long cache = BlockFile.append(file, table, List.of(TestSupport.fullBlock(1, 1, 0L, value)), -1L, true);
        timeline.putCacheOffset("pre.dat", cache);

        Compactor.Report report = new Compactor(snapshotRoot, timeline).compact();
        TestSupport.eq(0, report.filesDeleted(), "file kept for oldest live snapshot");
        TestSupport.eq(0L, report.blocksDropped(), "first block kept");
        TestSupport.check(Files.exists(file), "zpack still exists");
        Resolver resolver = new Resolver(snapshotRoot, timeline);
        present(resolver, "pre.dat", "S2", value, "S2 resolves first block");
        present(resolver, "pre.dat", "S5", value, "S5 resolves first block");
    }

    private static void present(Resolver resolver, String key, String slot, byte[] expected, String message)
            throws IOException {
        Resolver.Result result = resolver.resolve(key, 0L, slot);
        TestSupport.eq(Resolver.Kind.PRESENT, result.kind(), message + " kind");
        TestSupport.bytesEq(expected, result.data(), message);
    }
}
