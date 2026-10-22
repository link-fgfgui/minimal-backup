package io.github.linkfgfgui.minimalbackup.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** E7: 建点提交、模式选择、ABSENT、putNew 标记、崩溃孤儿、关键帧。 */
final class TestCheckpoint {
    private static final int KEYFRAME_INTERVAL = 20;

    private TestCheckpoint() {
    }

    static void run() throws Exception {
        Path dir = TestSupport.tempDir("checkpoint");
        try {
            basic(dir);
            noOpWrites(dir);
            keyframes(dir);
            bulkDeltas(dir);
            neverKeyframes(dir);
            crash(dir);
            branching(dir);
            branchNoOp(dir);
            branchAbsent(dir);
        } finally {
            TestSupport.deleteTree(dir);
        }
    }

    private static void basic(Path dir) throws IOException {
        Path snapshotRoot = dir.resolve("basic/minimalbackup_snapshots");
        Timeline timeline = Timeline.empty();
        Checkpoint checkpoint = new Checkpoint(snapshotRoot, timeline, TestSupport.SETTINGS);

        byte[] level1 = TestSupport.bytes(4096, 30);
        byte[] region1 = TestSupport.bytes(8192, 31);
        Checkpoint.ChangeSet first = new Checkpoint.ChangeSet()
                .put("level.dat", 0L, level1)
                .put("region/r.0.0.mca", 0L, Block.TYPE_CHUNK, region1)
                .absent("data/new.dat", 0L);
        Snapshot s1 = checkpoint.create(first, new Checkpoint.SnapshotInfo()
                .displayName("first").worldName("World").source("test"));
        TestSupport.eq("S1", s1.slot(), "first slot");
        TestSupport.eq(1, s1.generation(), "first generation");
        TestSupport.eq(3, s1.entryCount(), "first entry count");
        TestSupport.eq(level1.length + region1.length, s1.logicalBytes(), "first logical bytes");
        TestSupport.eq("S1", timeline.head(), "head after first");

        Resolver resolver = new Resolver(snapshotRoot, timeline);
        expectPresent(resolver, "level.dat", 0L, "S1", level1, "level at S1");
        expectPresent(resolver, "region/r.0.0.mca", 0L, "S1", region1, "region at S1");
        expectAbsent(resolver, "data/new.dat", 0L, "S1", "new.dat at S1");

        byte[] level2 = TestSupport.mutate(level1, 32);
        byte[] region2 = TestSupport.mutate(region1, 33);
        byte[] brandNew = TestSupport.text("brand new content");
        byte[] created = TestSupport.text("created");
        Checkpoint.ChangeSet second = new Checkpoint.ChangeSet()
                .put("level.dat", 0L, level2)
                .put("region/r.0.0.mca", 0L, Block.TYPE_CHUNK, region2)
                .putNew("data/new.dat", 0L, created)
                .putNew("data/brandnew.dat", 0L, brandNew)
                .absent("playerdata/x.dat", 0L);
        Snapshot s2 = checkpoint.create(second, new Checkpoint.SnapshotInfo().source("test"));
        TestSupport.eq("S2", s2.slot(), "second slot");
        TestSupport.eq(2, s2.generation(), "second generation");
        TestSupport.eq("S1", s2.parentSlot(), "second parent");
        TestSupport.eq(5, s2.entryCount(), "second entry count");

        Path levelFile = VaultPaths.zpackFor(snapshotRoot, "level.dat");
        BlockTable levelTable = BlockFile.loadTable(levelFile, timeline.cacheOffset("level.dat"));
        TestSupport.eq(2, levelTable.blocks().size(), "level block count");
        TestSupport.eq(Block.MODE_FULL, levelTable.byNumber(1).mode(), "level block1 full");
        TestSupport.eq(Block.MODE_DELTA, levelTable.byNumber(2).mode(), "level block2 delta");
        TestSupport.eq(1, levelTable.byNumber(2).dictionary(), "level block2 dictionary");

        Path brandFile = VaultPaths.zpackFor(snapshotRoot, "data/brandnew.dat");
        BlockTable brandTable = BlockFile.loadTable(brandFile, timeline.cacheOffset("data/brandnew.dat"));
        TestSupport.eq(2, brandTable.blocks().size(), "putNew marker block count");
        TestSupport.eq(Block.MODE_ABSENT, brandTable.byNumber(1).mode(), "putNew marker absent");
        TestSupport.eq(Block.MODE_FULL, brandTable.byNumber(2).mode(), "putNew content full");

        expectPresent(resolver, "level.dat", 0L, "S1", level1, "level still S1");
        expectPresent(resolver, "level.dat", 0L, "S2", level2, "level S2");
        expectPresent(resolver, "region/r.0.0.mca", 0L, "S1", region1, "region still S1");
        expectPresent(resolver, "region/r.0.0.mca", 0L, "S2", region2, "region S2");
        expectAbsent(resolver, "data/new.dat", 0L, "S1", "new.dat absent S1");
        expectPresent(resolver, "data/new.dat", 0L, "S2", created, "new.dat created S2");
        expectAbsent(resolver, "data/brandnew.dat", 0L, "S1", "brandnew absent S1");
        expectPresent(resolver, "data/brandnew.dat", 0L, "S2", brandNew, "brandnew S2");
        expectAbsent(resolver, "playerdata/x.dat", 0L, "S2", "explicit absent");
        expectAbsent(resolver, "playerdata/x.dat", 0L, "S1", "explicit absent seen by S1");

        Checkpoint.ChangeSet excluded = new Checkpoint.ChangeSet()
                .put("session.lock", 0L, TestSupport.text("lock"));
        Snapshot s3 = checkpoint.create(excluded, new Checkpoint.SnapshotInfo().source("test"));
        TestSupport.eq(0, s3.entryCount(), "excluded entry count");
        TestSupport.check(!Files.exists(VaultPaths.zpackFor(snapshotRoot, "session.lock")),
                "excluded file not written");

        byte[] pairOld = TestSupport.bytes(4096, 60);
        byte[] pairNew = TestSupport.mutate(pairOld, 61);
        Checkpoint.ChangeSet pair = new Checkpoint.ChangeSet()
                .put("pair.dat", 0L, pairOld)
                .put("pair.dat", 0L, pairNew);
        Snapshot s4 = checkpoint.create(pair, new Checkpoint.SnapshotInfo().source("test"));
        Path pairFile = VaultPaths.zpackFor(snapshotRoot, "pair.dat");
        BlockTable pairTable = BlockFile.loadTable(pairFile, timeline.cacheOffset("pair.dat"));
        TestSupport.eq(2, pairTable.blocks().size(), "baseline+latest block count");
        TestSupport.eq(Block.MODE_FULL, pairTable.byNumber(1).mode(), "baseline full");
        TestSupport.eq(Block.MODE_DELTA, pairTable.byNumber(2).mode(), "latest delta");
        TestSupport.eq(1, pairTable.byNumber(2).dictionary(), "latest dictionary");
        expectPresent(resolver, "pair.dat", 0L, s4.slot(), pairNew, "latest visible at S4");
        expectPresent(resolver, "pair.dat", 0L, "S3", pairOld, "baseline visible at S3");

        Checkpoint.ChangeSet tooMany = new Checkpoint.ChangeSet()
                .put("dup.dat", 0L, TestSupport.text("a"))
                .put("dup.dat", 0L, TestSupport.text("b"))
                .put("dup.dat", 0L, TestSupport.text("c"));
        TestSupport.expectIllegalArgument(
                () -> {
                    try {
                        checkpoint.create(tooMany, new Checkpoint.SnapshotInfo());
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                },
                "more than baseline+latest");
    }

    /**
     * P0: a captured write whose aligned bytes equal the previous stored block must not append a
     * block. The previous block stays the head, so the new snapshot resolves to the same value.
     */
    private static void noOpWrites(Path dir) throws IOException {
        Path snapshotRoot = dir.resolve("noop/minimalbackup_snapshots");
        Timeline timeline = Timeline.empty();
        Checkpoint checkpoint = new Checkpoint(snapshotRoot, timeline, TestSupport.SETTINGS);
        byte[] data1 = TestSupport.bytes(1024, 120);
        Snapshot s1 = checkpoint.create(new Checkpoint.ChangeSet().put("level.dat", 0L, data1),
                new Checkpoint.SnapshotInfo().source("test"));
        TestSupport.eq(1, s1.entryCount(), "initial entry");

        Snapshot s2 = checkpoint.create(new Checkpoint.ChangeSet().put("level.dat", 0L, data1),
                new Checkpoint.SnapshotInfo().source("test"));
        TestSupport.eq(0, s2.entryCount(), "no-op write skipped");
        TestSupport.eq(0L, s2.logicalBytes(), "no-op logical bytes");
        TestSupport.eq(0L, s2.physicalBytes(), "no-op physical bytes");
        Path file = VaultPaths.zpackFor(snapshotRoot, "level.dat");
        BlockTable table = BlockFile.loadTable(file, timeline.cacheOffset("level.dat"));
        TestSupport.eq(1, table.blocks().size(), "no-op did not append");

        Resolver resolver = new Resolver(snapshotRoot, timeline);
        expectPresent(resolver, "level.dat", 0L, "S2", data1, "no-op resolves");

        byte[] data2 = TestSupport.mutate(data1, 121);
        Snapshot s3 = checkpoint.create(new Checkpoint.ChangeSet()
                        .put("level.dat", 0L, data1)
                        .put("level.dat", 0L, data2),
                new Checkpoint.SnapshotInfo().source("test"));
        TestSupport.eq(1, s3.entryCount(), "baseline skipped, latest written");
        table = BlockFile.loadTable(file, timeline.cacheOffset("level.dat"));
        TestSupport.eq(2, table.blocks().size(), "baseline no-op did not append");
        TestSupport.eq(Block.MODE_DELTA, table.byNumber(2).mode(), "latest delta");
        expectPresent(resolver, "level.dat", 0L, "S3", data2, "latest resolves");

        Snapshot s4 = checkpoint.create(new Checkpoint.ChangeSet()
                        .put("level.dat", 0L, data2)
                        .put("level.dat", 0L, data2),
                new Checkpoint.SnapshotInfo().source("test"));
        TestSupport.eq(0, s4.entryCount(), "baseline+latest both no-op");
        table = BlockFile.loadTable(file, timeline.cacheOffset("level.dat"));
        TestSupport.eq(2, table.blocks().size(), "double no-op did not append");
        expectPresent(resolver, "level.dat", 0L, "S4", data2, "double no-op resolves");

        byte[] created = TestSupport.text("new");
        Snapshot s5 = checkpoint.create(new Checkpoint.ChangeSet()
                        .putNew("data/new.dat", 0L, created),
                new Checkpoint.SnapshotInfo().source("test"));
        TestSupport.eq(1, s5.entryCount(), "new target entry");
        BlockTable newTable = BlockFile.loadTable(
                VaultPaths.zpackFor(snapshotRoot, "data/new.dat"),
                timeline.cacheOffset("data/new.dat"));
        TestSupport.eq(2, newTable.blocks().size(), "new target marker+full");
        expectAbsent(resolver, "data/new.dat", 0L, "S4", "new target absent before S5");
        expectPresent(resolver, "data/new.dat", 0L, "S5", created, "new target present at S5");

        byte[] data3 = TestSupport.mutate(data2, 122);
        Snapshot s6 = checkpoint.create(new Checkpoint.ChangeSet()
                        .put("level.dat", 0L, data3)
                        .put("level.dat", 0L, data3),
                new Checkpoint.SnapshotInfo().source("test"));
        TestSupport.eq(1, s6.entryCount(), "baseline written, latest no-op");
        table = BlockFile.loadTable(file, timeline.cacheOffset("level.dat"));
        TestSupport.eq(3, table.blocks().size(), "latest no-op after baseline");
        TestSupport.eq(Block.MODE_DELTA, table.byNumber(3).mode(), "baseline delta");
        expectPresent(resolver, "level.dat", 0L, "S6", data3, "baseline value resolves");

        Snapshot s7 = checkpoint.create(new Checkpoint.ChangeSet()
                        .absent("data/gone.dat", 0L),
                new Checkpoint.SnapshotInfo().source("test"));
        TestSupport.eq(1, s7.entryCount(), "first explicit absent");
        Snapshot s8 = checkpoint.create(new Checkpoint.ChangeSet()
                        .absent("data/gone.dat", 0L),
                new Checkpoint.SnapshotInfo().source("test"));
        TestSupport.eq(0, s8.entryCount(), "repeated absent skipped");
        BlockTable goneTable = BlockFile.loadTable(
                VaultPaths.zpackFor(snapshotRoot, "data/gone.dat"),
                timeline.cacheOffset("data/gone.dat"));
        TestSupport.eq(1, goneTable.blocks().size(), "repeated absent did not append");
        expectAbsent(resolver, "data/gone.dat", 0L, "S8", "repeated absent resolves");
    }

    private static void keyframes(Path dir) throws IOException {
        Path snapshotRoot = dir.resolve("keyframes/minimalbackup_snapshots");
        Timeline timeline = Timeline.empty();
        EngineSettings settings = new EngineSettings(TestSupport.SETTINGS.zstdLevel(),
                TestSupport.SETTINGS.zstdLong(), TestSupport.SETTINGS.zstdLongWindowLog(),
                KEYFRAME_INTERVAL, true);
        Checkpoint checkpoint = new Checkpoint(snapshotRoot, timeline, settings);
        byte[] data = TestSupport.bytes(256, 40);
        for (int i = 1; i <= KEYFRAME_INTERVAL + 1; i++) {
            if (i > 1) {
                data = TestSupport.mutate(data, 40 + i);
            }
            Checkpoint.ChangeSet changes = new Checkpoint.ChangeSet().put("data.dat", 0L, data);
            checkpoint.create(changes, new Checkpoint.SnapshotInfo().source("test"));
        }
        Path file = VaultPaths.zpackFor(snapshotRoot, "data.dat");
        BlockTable table = BlockFile.loadTable(file, timeline.cacheOffset("data.dat"));
        TestSupport.eq(KEYFRAME_INTERVAL + 1, table.blocks().size(),
                "keyframe block count");
        TestSupport.eq(Block.MODE_FULL, table.byNumber(KEYFRAME_INTERVAL + 1).mode(),
                "keyframe at interval");
        TestSupport.eq(Block.MODE_DELTA, table.byNumber(KEYFRAME_INTERVAL).mode(),
                "block before keyframe delta");
    }

    /**
     * A save that touches many targets at once must still use deltas. Counting the keyframe
     * distance in file-wide block numbers made every target look 20+ blocks old, so a bulk save
     * rewrote every chunk as a FULL block.
     */
    private static void bulkDeltas(Path dir) throws IOException {
        Path snapshotRoot = dir.resolve("bulk/minimalbackup_snapshots");
        Timeline timeline = Timeline.empty();
        Checkpoint checkpoint = new Checkpoint(snapshotRoot, timeline, TestSupport.SETTINGS);
        int chunks = 40;
        byte[][] first = new byte[chunks][];
        Checkpoint.ChangeSet one = new Checkpoint.ChangeSet();
        for (int i = 0; i < chunks; i++) {
            first[i] = TestSupport.bytes(512, 300 + i);
            one.put("region/r.0.0.mca", i, Block.TYPE_CHUNK, first[i]);
        }
        checkpoint.create(one, new Checkpoint.SnapshotInfo().source("test"));
        Checkpoint.ChangeSet two = new Checkpoint.ChangeSet();
        for (int i = 0; i < chunks; i++) {
            two.put("region/r.0.0.mca", i, Block.TYPE_CHUNK, TestSupport.mutate(first[i], 400 + i));
        }
        checkpoint.create(two, new Checkpoint.SnapshotInfo().source("test"));
        BlockTable table = BlockFile.loadTable(
                VaultPaths.zpackFor(snapshotRoot, "region/r.0.0.mca"),
                timeline.cacheOffset("region/r.0.0.mca"));
        int deltas = 0;
        for (Block block : table.blocks()) {
            if (block.mode() == Block.MODE_DELTA) {
                deltas++;
            }
        }
        TestSupport.eq(chunks, deltas, "bulk save keeps deltas");
    }

    /** The default interval (-1) never writes a keyframe, however long the delta chain grows. */
    private static void neverKeyframes(Path dir) throws IOException {
        Path snapshotRoot = dir.resolve("never/minimalbackup_snapshots");
        Timeline timeline = Timeline.empty();
        Checkpoint checkpoint = new Checkpoint(snapshotRoot, timeline, TestSupport.SETTINGS);
        byte[] data = TestSupport.bytes(256, 90);
        int versions = KEYFRAME_INTERVAL + 5;
        for (int i = 1; i <= versions; i++) {
            if (i > 1) {
                data = TestSupport.mutate(data, 90 + i);
            }
            checkpoint.create(new Checkpoint.ChangeSet().put("data.dat", 0L, data),
                    new Checkpoint.SnapshotInfo().source("test"));
        }
        BlockTable table = BlockFile.loadTable(VaultPaths.zpackFor(snapshotRoot, "data.dat"),
                timeline.cacheOffset("data.dat"));
        TestSupport.eq(versions, table.blocks().size(), "one block per version");
        TestSupport.eq(Block.MODE_FULL, table.byNumber(1).mode(), "first block full");
        for (int number = 2; number <= versions; number++) {
            TestSupport.eq(Block.MODE_DELTA, table.byNumber(number).mode(),
                    "no keyframe at block " + number);
        }
    }

    private static void crash(Path dir) throws IOException {
        Path snapshotRoot = dir.resolve("crash/minimalbackup_snapshots");
        Timeline timeline = Timeline.empty();
        Checkpoint checkpoint = new Checkpoint(snapshotRoot, timeline, TestSupport.SETTINGS);
        byte[] first = TestSupport.bytes(1024, 50);
        checkpoint.create(new Checkpoint.ChangeSet().put("level.dat", 0L, first),
                new Checkpoint.SnapshotInfo().source("test"));
        byte[] second = TestSupport.mutate(first, 51);
        Snapshot committed = checkpoint.create(new Checkpoint.ChangeSet().put("level.dat", 0L, second),
                new Checkpoint.SnapshotInfo().source("test"));

        Path file = VaultPaths.zpackFor(snapshotRoot, "level.dat");
        Long cache = timeline.cacheOffset("level.dat");
        BlockTable table = BlockFile.loadTable(file, cache);
        byte[] orphan = TestSupport.bytes(1024, 52);
        BlockFile.append(file, table,
                List.of(TestSupport.fullBlock(table.nextBlockNumber(), 99, 0L, orphan)), cache, true);

        Timeline reloaded = Timeline.load(snapshotRoot.resolve(VaultPaths.INDEX_FILE_NAME));
        Resolver resolver = new Resolver(snapshotRoot, reloaded);
        expectPresent(resolver, "level.dat", 0L, committed.slot(), second, "committed survives crash");
        TestSupport.eq(2, reloaded.size(), "timeline unchanged by orphan");

        SelfHealing.Report report = new SelfHealing().heal(snapshotRoot, reloaded);
        TestSupport.check(report.orphanBlocks() >= 1, "orphan reported");
        TestSupport.eq(0, report.filesTruncated(), "clean file not truncated");
    }

    /**
     * A restore moves the head to the restored slot; the next checkpoint must branch there while
     * the abandoned chain keeps its own parents and data.
     */
    private static void branching(Path dir) throws IOException {
        Path snapshotRoot = dir.resolve("branching/minimalbackup_snapshots");
        Timeline timeline = Timeline.empty();
        Checkpoint checkpoint = new Checkpoint(snapshotRoot, timeline, TestSupport.SETTINGS);
        byte[] data = TestSupport.bytes(512, 70);
        byte[] atS3 = null;
        for (int generation = 1; generation <= 5; generation++) {
            if (generation > 1) {
                data = TestSupport.mutate(data, 70 + generation);
            }
            if (generation == 3) {
                atS3 = data;
            }
            checkpoint.create(new Checkpoint.ChangeSet().put("level.dat", 0L, data),
                    new Checkpoint.SnapshotInfo().source("test"));
        }
        TestSupport.eq("S5", timeline.head(), "chain head before restore");

        // What completeRestore does once the restore gate passes.
        timeline.setHead("S3");
        byte[] afterRestore = TestSupport.mutate(data, 80);
        Snapshot branch = checkpoint.create(
                new Checkpoint.ChangeSet().put("level.dat", 0L, afterRestore),
                new Checkpoint.SnapshotInfo().source("test"));
        TestSupport.eq("S6", branch.slot(), "branch slot");
        TestSupport.eq("S3", branch.parentSlot(), "branch parent is the restored slot");
        TestSupport.eq("S6", timeline.head(), "branch becomes head");
        int children = 0;
        for (Snapshot snapshot : timeline.snapshots()) {
            if ("S3".equals(snapshot.parentSlot())) {
                children++;
            }
        }
        TestSupport.eq(2, children, "restored node has two children");
        TestSupport.eq("S2", timeline.snapshot("S3").parentSlot(), "restored node keeps its parent");
        TestSupport.eq("S4", timeline.snapshot("S5").parentSlot(), "abandoned chain keeps its parent");

        Resolver resolver = new Resolver(snapshotRoot, timeline);
        expectPresent(resolver, "level.dat", 0L, "S3", atS3, "restored node still resolves");
        expectPresent(resolver, "level.dat", 0L, "S5", data, "abandoned chain still resolves");
        expectPresent(resolver, "level.dat", 0L, "S6", afterRestore, "branch resolves");

        Timeline reloaded = Timeline.load(snapshotRoot.resolve(VaultPaths.INDEX_FILE_NAME));
        TestSupport.eq("S6", reloaded.head(), "branch head persisted");
        TestSupport.eq("S3", reloaded.snapshot("S6").parentSlot(), "branch parent persisted");
        TestSupport.eq("S4", reloaded.snapshot("S5").parentSlot(), "old chain persisted");
    }

    /**
     * P0 must not use a block from an abandoned branch as the no-op base: after a restore the
     * highest block may not be visible, and skipping it would make the new snapshot resolve to
     * an older, different value.
     */
    private static void branchNoOp(Path dir) throws IOException {
        Path snapshotRoot = dir.resolve("branchnoop/minimalbackup_snapshots");
        Timeline timeline = Timeline.empty();
        Checkpoint checkpoint = new Checkpoint(snapshotRoot, timeline, TestSupport.SETTINGS);
        byte[] data = TestSupport.bytes(512, 140);
        byte[] atS2 = null;
        for (int generation = 1; generation <= 5; generation++) {
            if (generation > 1) {
                data = TestSupport.mutate(data, 140 + generation);
            }
            if (generation == 2) {
                atS2 = data;
            }
            checkpoint.create(new Checkpoint.ChangeSet().put("level.dat", 0L, data),
                    new Checkpoint.SnapshotInfo().source("test"));
        }
        byte[] abandoned = data;
        timeline.setHead("S2");
        Snapshot branch = checkpoint.create(
                new Checkpoint.ChangeSet().put("level.dat", 0L, abandoned),
                new Checkpoint.SnapshotInfo().source("test"));
        TestSupport.eq(1, branch.entryCount(), "abandoned block is not a no-op base");

        Resolver resolver = new Resolver(snapshotRoot, timeline);
        expectPresent(resolver, "level.dat", 0L, branch.slot(), abandoned,
                "branch writes the abandoned value");
        expectPresent(resolver, "level.dat", 0L, "S2", atS2, "old branch keeps its value");

        Snapshot noOp = checkpoint.create(
                new Checkpoint.ChangeSet().put("level.dat", 0L, abandoned),
                new Checkpoint.SnapshotInfo().source("test"));
        TestSupport.eq(0, noOp.entryCount(), "visible no-op skipped after branch");
        expectPresent(resolver, "level.dat", 0L, noOp.slot(), abandoned,
                "visible no-op resolves to the branch value");
    }

    /** An ABSENT block from an abandoned branch must not turn a real deletion into a no-op. */
    private static void branchAbsent(Path dir) throws IOException {
        Path snapshotRoot = dir.resolve("branchabsent/minimalbackup_snapshots");
        Timeline timeline = Timeline.empty();
        Checkpoint checkpoint = new Checkpoint(snapshotRoot, timeline, TestSupport.SETTINGS);
        byte[] data1 = TestSupport.bytes(384, 150);
        checkpoint.create(new Checkpoint.ChangeSet().put("level.dat", 0L, data1),
                new Checkpoint.SnapshotInfo().source("test"));
        checkpoint.create(new Checkpoint.ChangeSet().absent("level.dat", 0L),
                new Checkpoint.SnapshotInfo().source("test"));
        byte[] data2 = TestSupport.mutate(data1, 151);
        checkpoint.create(new Checkpoint.ChangeSet().put("level.dat", 0L, data2),
                new Checkpoint.SnapshotInfo().source("test"));
        checkpoint.create(new Checkpoint.ChangeSet().absent("level.dat", 0L),
                new Checkpoint.SnapshotInfo().source("test"));

        timeline.setHead("S1");
        Snapshot branch = checkpoint.create(
                new Checkpoint.ChangeSet().absent("level.dat", 0L),
                new Checkpoint.SnapshotInfo().source("test"));
        TestSupport.eq(1, branch.entryCount(), "abandoned ABSENT is not a no-op base");

        Resolver resolver = new Resolver(snapshotRoot, timeline);
        expectAbsent(resolver, "level.dat", 0L, branch.slot(),
                "branch deletion is visible");
        expectPresent(resolver, "level.dat", 0L, "S1", data1,
                "old branch keeps its value");
    }

    private static void expectPresent(Resolver resolver, String key, long subTarget, String slot,
                                      byte[] expected, String message) throws IOException {
        Resolver.Result result = resolver.resolve(key, subTarget, slot);
        TestSupport.eq(Resolver.Kind.PRESENT, result.kind(), message + " kind");
        TestSupport.bytesEq(expected, result.data(), message);
    }

    private static void expectAbsent(Resolver resolver, String key, long subTarget, String slot,
                                     String message) throws IOException {
        TestSupport.eq(Resolver.Kind.ABSENT, resolver.resolve(key, subTarget, slot).kind(), message);
    }
}
