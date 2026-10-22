package io.github.linkfgfgui.minimalbackup.engine;

import io.github.linkfgfgui.minimalbackup.engine.anvil.AnvilRegion;
import io.github.linkfgfgui.minimalbackup.engine.cli.Main;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** E10: 引擎建点 → CLI list/rollback/compact/heal → 独立 CLI 子进程。 */
final class TestEndToEnd {
    private TestEndToEnd() {
    }

    static void run() throws Exception {
        Path dir = TestSupport.tempDir("e2e");
        try {
            Path gameDir = dir.resolve("game");
            Path target1 = dir.resolve("restore1");
            Path target2 = dir.resolve("restore2");
            Path vault = VaultPaths.vaultRoot(gameDir, "World");
            Path snapshotRoot = VaultPaths.snapshotRoot(vault);
            Timeline timeline = Timeline.load(VaultPaths.indexFile(vault));
            Checkpoint checkpoint = new Checkpoint(snapshotRoot, timeline, TestSupport.SETTINGS);

            byte[] level1 = TestSupport.bytes(16 * 1024, 80);
            byte[] level2 = TestSupport.mutate(level1, 90);
            byte[] score = TestSupport.bytes(4096, 85);
            byte[] map = TestSupport.bytes(1024, 86);
            byte[] player = TestSupport.bytes(2048, 84);
            byte[] added = TestSupport.bytes(3072, 92);
            long chunk00 = AnvilRegion.chunkSubTarget(0, 0);
            long chunk10 = AnvilRegion.chunkSubTarget(1, 0);
            long chunk20 = AnvilRegion.chunkSubTarget(2, 0);
            byte[] chunk00v1 = TestSupport.chunkNbt(4096, 100);
            byte[] chunk00v2 = TestSupport.chunkNbt(4096, 101);
            byte[] chunk10v2 = TestSupport.chunkNbt(2048, 102);
            byte[] chunk20v1 = TestSupport.chunkNbt(2048, 103);

            Checkpoint.ChangeSet first = new Checkpoint.ChangeSet()
                    .putNew("level.dat", 0L, Block.TYPE_FILE, level1)
                    .putNew("data/scoreboard.dat", 0L, Block.TYPE_FILE, score)
                    .putNew("data/map_0.dat", 0L, Block.TYPE_FILE, map)
                    .putNew("playerdata/069a79.dat", 0L, Block.TYPE_FILE, player)
                    .putNew("region/r.0.0.mca", chunk00, Block.TYPE_CHUNK, chunk00v1)
                    .putNew("region/r.0.0.mca", chunk20, Block.TYPE_CHUNK, chunk20v1);
            Snapshot s1 = checkpoint.create(first, new Checkpoint.SnapshotInfo()
                    .displayName("first").source("e2e"));
            TestSupport.eq("S1", s1.slot(), "S1 slot");
            TestSupport.eq(1, timeline.size(), "S1 in index");
            TestSupport.eq("S1", timeline.head(), "S1 head");
            TestSupport.check(Files.exists(VaultPaths.zpackFor(snapshotRoot, "level.dat")),
                    "level zpack");
            TestSupport.check(!Files.exists(VaultPaths.zpackFor(snapshotRoot,
                    "region/r.1.0.mca")), "unrelated region absent");

            Checkpoint.ChangeSet second = new Checkpoint.ChangeSet()
                    .put("level.dat", 0L, Block.TYPE_FILE, level2)
                    .absent("playerdata/069a79.dat", 0L, Block.TYPE_FILE)
                    .putNew("data/new.dat", 0L, Block.TYPE_FILE, added)
                    .put("region/r.0.0.mca", chunk00, Block.TYPE_CHUNK, chunk00v2)
                    .absent("region/r.0.0.mca", chunk20, Block.TYPE_CHUNK)
                    .putNew("region/r.0.0.mca", chunk10, Block.TYPE_CHUNK, chunk10v2);
            Snapshot s2 = checkpoint.create(second, new Checkpoint.SnapshotInfo()
                    .displayName("second").source("e2e"));
            TestSupport.eq("S2", s2.slot(), "S2 slot");
            TestSupport.eq(2, timeline.size(), "S2 in index");
            TestSupport.eq("S2", timeline.head(), "S2 head");

            TestSupport.eq(0, Main.run(new String[]{"list", gameDir.toString(), "World"}), "list");

            Files.createDirectories(target1);
            TestSupport.eq(0, Main.run(new String[]{"rollback", gameDir.toString(), "World",
                    "S1", target1.toString()}), "rollback S1");
            TestSupport.bytesEq(level1, Files.readAllBytes(target1.resolve("level.dat")),
                    "S1 level.dat");
            TestSupport.bytesEq(score, Files.readAllBytes(target1.resolve("data/scoreboard.dat")),
                    "S1 data");
            TestSupport.bytesEq(player, Files.readAllBytes(target1.resolve(
                    "playerdata/069a79.dat")), "S1 playerdata");
            TestSupport.check(!Files.exists(target1.resolve("data/new.dat")),
                    "S1 has no later file");
            Path region1 = target1.resolve("region/r.0.0.mca");
            TestSupport.check(Files.isRegularFile(region1), "S1 region exists");
            TestSupport.eq(0L, Files.size(region1) % AnvilRegion.SECTOR_BYTES,
                    "S1 region is sector aligned");

            TestSupport.copyTree(target1, target2);
            TestSupport.eq(0, Main.run(new String[]{"rollback", gameDir.toString(), "World",
                    "S2", target2.toString()}), "rollback S2");
            TestSupport.bytesEq(level2, Files.readAllBytes(target2.resolve("level.dat")),
                    "S2 level.dat");
            TestSupport.check(!Files.exists(target2.resolve("playerdata/069a79.dat")),
                    "S2 playerdata deleted");
            TestSupport.bytesEq(added, Files.readAllBytes(target2.resolve("data/new.dat")),
                    "S2 added file");
            TestSupport.bytesEq(score, Files.readAllBytes(target2.resolve("data/scoreboard.dat")),
                    "S2 unchanged file keeps target bytes");

            Path levelFile = VaultPaths.zpackFor(snapshotRoot, "level.dat");
            Long levelCache = timeline.cacheOffset("level.dat");
            BlockTable levelTable = BlockFile.loadTable(levelFile, levelCache);
            BlockFile.append(levelFile, levelTable,
                    List.of(TestSupport.fullBlock(levelTable.nextBlockNumber(), 99, 0L,
                            TestSupport.bytes(1024, 95))), levelCache, true);
            TestSupport.eq(0, Main.run(new String[]{"compact", gameDir.toString(), "World"}),
                    "compact");
            BlockTable compactedLevel = BlockFile.loadTable(levelFile,
                    Timeline.load(VaultPaths.indexFile(vault)).cacheOffset("level.dat"));
            TestSupport.check(compactedLevel.blocks().stream()
                    .noneMatch(block -> block.generation() == 99), "orphan removed by CLI compact");

            Path target1b = dir.resolve("restore1b");
            TestSupport.copyTree(target2, target1b);
            TestSupport.eq(0, Main.run(new String[]{"rollback", gameDir.toString(), "World",
                    "S1", target1b.toString()}), "rollback S1 after compact");
            TestSupport.bytesEq(level1, Files.readAllBytes(target1b.resolve("level.dat")),
                    "post-compact S1 level.dat");

            TestSupport.eq(0, Main.run(new String[]{"heal", gameDir.toString(), "World"}), "heal");
            TestSupport.check(noTempFiles(target1), "rollback temp files cleaned");

            standalone(gameDir);
        } finally {
            TestSupport.deleteTree(dir);
        }
    }

    private static boolean noTempFiles(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            return walk.noneMatch(path -> path.getFileName().toString()
                    .endsWith(AtomicFiles.TEMP_SUFFIX));
        }
    }

    private static void standalone(Path gameDir) throws IOException, InterruptedException {
        String java = System.getProperty("java.home") + "/bin/java";
        String classpath = System.getProperty("java.class.path");
        ProcessBuilder builder = new ProcessBuilder(java, "-cp", classpath,
                "io.github.linkfgfgui.minimalbackup.engine.cli.Main",
                "list", gameDir.toString(), "World");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = process.waitFor();
        TestSupport.eq(0, code, "standalone CLI exit code");
        TestSupport.check(output.contains("head=S2"), "standalone CLI output: " + output);
        TestSupport.check(output.contains("S1") && output.contains("S2"),
                "standalone lists snapshots");
    }
}
