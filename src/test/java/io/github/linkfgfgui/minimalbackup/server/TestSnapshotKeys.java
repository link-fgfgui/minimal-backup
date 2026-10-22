package io.github.linkfgfgui.minimalbackup.server;

import java.nio.file.Path;

/**
 * SnapshotKeysTest: dimension layouts, .mca naming, unknown layouts.
 *
 * <p>Keys are derived by relativising the real folder against the world directory, so the test
 * covers the three layouts the game actually uses rather than a hard-coded dimension table.
 */
public final class TestSnapshotKeys {
    private TestSnapshotKeys() {
    }

    public static void run() {
        Path world = Path.of("/game/saves/World");

        // 1.20.1: overworld and DIM-1/DIM1 live directly under the world directory.
        TestServerSupport.eq("region/r.0.0.mca",
                SnapshotKeys.chunkKey(world, world.resolve("region"), 0, 0), "overworld 1.20.1");
        TestServerSupport.eq("DIM-1/region/r.-1.2.mca",
                SnapshotKeys.chunkKey(world, world.resolve("DIM-1/region"), -1, 2), "nether 1.20.1");
        TestServerSupport.eq("DIM1/region/r.3.4.mca",
                SnapshotKeys.chunkKey(world, world.resolve("DIM1/region"), 3, 4), "end 1.20.1");

        // 1.21.1: every dimension is nested under dimensions/<namespace>/<path>.
        TestServerSupport.eq("dimensions/minecraft/overworld/region/r.0.0.mca",
                SnapshotKeys.chunkKey(world, world.resolve("dimensions/minecraft/overworld/region"), 0, 0),
                "overworld 1.21.1");
        TestServerSupport.eq("dimensions/minecraft/the_nether/entities/r.1.1.mca",
                SnapshotKeys.chunkKey(world, world.resolve("dimensions/minecraft/the_nether/entities"), 1, 1),
                "entities folder");

        // A folder outside the world is not addressable.
        TestServerSupport.isNull(SnapshotKeys.chunkKey(world, Path.of("/elsewhere/region"), 0, 0),
                "outside world");

        // File keys keep their relative path and normalise separators.
        TestServerSupport.eq("level.dat",
                SnapshotKeys.fileKey(world, world.resolve("level.dat")), "level.dat key");
        TestServerSupport.eq("data/scoreboard.dat",
                SnapshotKeys.fileKey(world, world.resolve("data/scoreboard.dat")), "saved data key");
        TestServerSupport.eq("playerdata/00000000-0000-0000-0000-000000000000.dat",
                SnapshotKeys.fileKey(world, world.resolve("playerdata/00000000-0000-0000-0000-000000000000.dat")),
                "player data key");
        TestServerSupport.isNull(SnapshotKeys.fileKey(world, Path.of("/elsewhere/level.dat")),
                "file outside world");

        // ChunkPos packing: x in the low half, z in the high half, negatives preserved.
        TestServerSupport.eq(0L, SnapshotKeys.chunkSubTarget(0, 0), "chunk 0,0");
        TestServerSupport.eq(0x0000000200000001L, SnapshotKeys.chunkSubTarget(1, 2), "chunk 1,2");
        TestServerSupport.eq(0xFFFFFFFF00000000L, SnapshotKeys.chunkSubTarget(0, -1), "chunk 0,-1");
        TestServerSupport.eq(0xFFFFFFFEFFFFFFFFL, SnapshotKeys.chunkSubTarget(-1, -2), "chunk -1,-2");
        TestServerSupport.check(SnapshotKeys.chunkSubTarget(7, 9) != SnapshotKeys.chunkSubTarget(9, 7),
                "packing must be order sensitive");
    }
}
