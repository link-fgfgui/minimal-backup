package io.github.linkfgfgui.minimalbackup.server;

import io.github.linkfgfgui.minimalbackup.engine.Checkpoint;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HookCoverageTest: every write entry point named in the plan maps to a distinct snapshot target.
 */
public final class TestHookCoverage {
    private TestHookCoverage() {
    }

    public static void run(Path gameDir) throws Exception {
        Path world = HookTestSupport.world(gameDir, "CoverageWorld");
        BackupRuntime runtime = BackupRuntime.init(world);

        Map<String, Long> expected = new LinkedHashMap<>();
        expected.put("region/r.0.0.mca", SnapshotKeys.chunkSubTarget(0, 0));
        expected.put("entities/r.0.0.mca", SnapshotKeys.chunkSubTarget(0, 0));
        expected.put("poi/r.0.0.mca", SnapshotKeys.chunkSubTarget(0, 0));
        expected.put("data/scoreboard.dat", 0L);
        expected.put("data/raids.dat", 0L);
        expected.put("playerdata/00000000-0000-0000-0000-000000000001.dat", 0L);
        expected.put("level.dat", 0L);

        runtime.captureChunkWrite(world.resolve("region"), 0, 0, HookTestSupport.tag("chunk", 1), () -> null);
        runtime.captureChunkWrite(world.resolve("entities"), 0, 0, HookTestSupport.tag("entities", 1), () -> null);
        runtime.captureChunkWrite(world.resolve("poi"), 0, 0, HookTestSupport.tag("poi", 1), () -> null);
        runtime.captureFileWrite(world.resolve("data/scoreboard.dat"), HookTestSupport.tag("scoreboard", 1), () -> null);
        runtime.captureFileWrite(world.resolve("data/raids.dat"), HookTestSupport.tag("raids", 1), () -> null);
        runtime.captureFileWrite(world.resolve("playerdata/00000000-0000-0000-0000-000000000001.dat"),
                HookTestSupport.tag("player", 1), () -> null);
        runtime.captureFileWrite(world.resolve("level.dat"), HookTestSupport.tag("level", 1), () -> null);

        Map<String, Long> captured = new LinkedHashMap<>();
        for (Checkpoint.ChangeSet.Entry entry : HookTestSupport.entries(runtime)) {
            if (!entry.absent()) {
                captured.put(entry.relPath(), entry.subTarget());
            }
        }
        for (Map.Entry<String, Long> entry : expected.entrySet()) {
            HookTestSupport.check(captured.containsKey(entry.getKey()),
                    "missing capture for " + entry.getKey());
            HookTestSupport.eq(entry.getValue(), captured.get(entry.getKey()),
                    "sub-target for " + entry.getKey());
        }
        HookTestSupport.eq(expected.size(), captured.size(), "no unexpected targets");

        // SavedData read service uses the same file key.
        runtime.createSnapshot("test", "coverage");
        runtime.arm("S1");
        HookTestSupport.eq(io.github.linkfgfgui.minimalbackup.engine.Resolver.Kind.PRESENT,
                runtime.resolveData(world.resolve("data").toString(), "scoreboard").kind(),
                "saved data resolves through the file key");
        runtime.disarm();
    }
}
