package io.github.linkfgfgui.minimalbackup.server;

import io.github.linkfgfgui.minimalbackup.engine.Resolver;
import io.github.linkfgfgui.minimalbackup.engine.Snapshot;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.nbt.CompoundTag;

/** ReadCaptureTest: cold reads become the baseline, repeats do not, and restores see them. */
public final class TestReadCapture {
    private TestReadCapture() {
    }

    public static void run(Path gameDir) throws Exception {
        Path world = HookTestSupport.world(gameDir, "ReadWorld");
        BackupRuntime runtime = BackupRuntime.init(world);
        Path folder = world.resolve("region");

        CompoundTag first = HookTestSupport.tag("first-read", 1);
        runtime.captureChunkReadAt(folder, 16, 16, first);
        List<io.github.linkfgfgui.minimalbackup.engine.Checkpoint.ChangeSet.Entry> entries =
                HookTestSupport.entries(runtime);
        HookTestSupport.eq(1, entries.size(), "cold read captured");
        HookTestSupport.eq("region/r.0.0.mca", entries.get(0).relPath(), "chunk path");
        HookTestSupport.eq(SnapshotKeys.chunkSubTarget(16, 16), entries.get(0).subTarget(),
                "chunk sub-target");

        // Repeats must not replace the baseline.
        runtime.captureChunkReadAt(folder, 16, 16, HookTestSupport.tag("second-read", 2));
        HookTestSupport.eq(1, HookTestSupport.entries(runtime).size(), "repeat read is ignored");
        HookTestSupport.eq("first-read",
                HookTestSupport.text(HookTestSupport.entries(runtime).get(0).data()),
                "baseline stays the first value");

        // Capture must not require the folder to exist on disk.
        Path virtualFolder = world.resolve("region_that_does_not_exist");
        runtime.captureChunkReadAt(virtualFolder, 0, 0, HookTestSupport.tag("virtual", 3));
        HookTestSupport.eq(2, HookTestSupport.entries(runtime).size(), "virtual folder captured");

        Snapshot snapshot = runtime.createSnapshot("test", "read capture");
        runtime.arm(snapshot.slot());
        Resolver.Result result = runtime.resolveChunk(folder, 16, 16);
        HookTestSupport.eq(Resolver.Kind.PRESENT, result.kind(), "armed read is served");
        HookTestSupport.eq("first-read", HookTestSupport.marker(BackupHooks.tag(result)),
                "served value matches the captured baseline");
        HookTestSupport.eq(1L, runtime.coverHits(), "coverHits counts the served chunk");

        // A target with no block passes through.
        Resolver.Result miss = runtime.resolveChunk(folder, 512, 512);
        HookTestSupport.eq(Resolver.Kind.PASSTHROUGH, miss.kind(), "unknown chunk passes through");
        runtime.disarm();
    }
}
