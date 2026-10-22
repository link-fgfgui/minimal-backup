package io.github.linkfgfgui.minimalbackup.server;

import io.github.linkfgfgui.minimalbackup.config.ConfigHolder;
import io.github.linkfgfgui.minimalbackup.engine.Resolver;
import io.github.linkfgfgui.minimalbackup.engine.Snapshot;
import io.github.linkfgfgui.minimalbackup.engine.Timeline;
import io.github.linkfgfgui.minimalbackup.engine.VaultPaths;
import java.nio.file.Path;

/**
 * RestoreBranchTest: a completed restore moves the head to the restored snapshot, so the next
 * checkpoint branches off it instead of continuing the abandoned chain.
 */
public final class TestRestoreBranch {
    private TestRestoreBranch() {
    }

    public static void run(Path gameDir) throws Exception {
        Path world = HookTestSupport.world(gameDir, "BranchWorld");
        BackupRuntime runtime = BackupRuntime.init(world);
        Path folder = world.resolve("region");

        for (int generation = 1; generation <= 5; generation++) {
            runtime.captureChunkWrite(folder, 0, 0,
                    HookTestSupport.tag("gen" + generation, generation), () -> null);
            runtime.createSnapshot("test", "gen" + generation);
        }
        HookTestSupport.eq("S5", runtime.timeline().head(), "head before restore");

        // With forceDirty=false the restore needs no live server, so the gate can be completed
        // here exactly as the server-start hook does it.
        ConfigHolder.values().set("restore.forceDirty", false);
        try {
            Snapshot target = runtime.prepareRestore("S3");
            HookTestSupport.eq("S3", target.slot(), "armed the middle snapshot");
            HookTestSupport.eq(Resolver.Kind.PRESENT, runtime.resolveChunk(folder, 0, 0).kind(),
                    "restored chunk is served");
            HookTestSupport.check(runtime.completeRestore(null), "restore completes");
        } finally {
            ConfigHolder.values().set("restore.forceDirty", true);
        }

        HookTestSupport.eq("S3", runtime.timeline().head(), "restored slot is the new head");
        HookTestSupport.eq("S3", reload(runtime).head(), "restored head persisted");

        runtime.captureChunkWrite(folder, 32, 32, HookTestSupport.tag("branch", 9), () -> null);
        Snapshot branch = runtime.createSnapshot("test", "after restore");
        HookTestSupport.eq("S6", branch.slot(), "branch slot");
        HookTestSupport.eq("S3", branch.parentSlot(), "branch parent is the restored slot");

        Timeline reloaded = reload(runtime);
        HookTestSupport.eq("S6", reloaded.head(), "branch head persisted");
        HookTestSupport.eq("S3", reloaded.snapshot("S6").parentSlot(), "branch parent persisted");
        HookTestSupport.eq("S4", reloaded.snapshot("S5").parentSlot(), "old chain persisted");
        int children = 0;
        for (Snapshot snapshot : reloaded.snapshots()) {
            if ("S3".equals(snapshot.parentSlot())) {
                children++;
            }
        }
        HookTestSupport.eq(2, children, "restored node has two children");
        runtime.disarm();
    }

    private static Timeline reload(BackupRuntime runtime) throws Exception {
        return Timeline.load(VaultPaths.indexFile(runtime.snapshotRoot().getParent()));
    }
}
