package io.github.linkfgfgui.minimalbackup.server;

import io.github.linkfgfgui.minimalbackup.engine.Checkpoint;
import io.github.linkfgfgui.minimalbackup.engine.Snapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.nbt.CompoundTag;

/**
 * CowFallbackTest: the first tracked write to a target that has no block must capture the value
 * that is about to be overwritten.
 */
public final class TestCowFallback {
    private TestCowFallback() {
    }

    public static void run(Path gameDir) throws Exception {
        Path world = HookTestSupport.world(gameDir, "CowWorld");
        BackupRuntime runtime = BackupRuntime.init(world);
        Path folder = world.resolve("region");

        // 1. Value exists on disk but was never read: baseline is the old value, latest the new one.
        CompoundTag oldTag = HookTestSupport.tag("old", 1);
        CompoundTag newTag = HookTestSupport.tag("new", 2);
        runtime.captureChunkWrite(folder, 0, 0, newTag, () -> oldTag);
        List<Checkpoint.ChangeSet.Entry> entries = HookTestSupport.entries(runtime);
        HookTestSupport.eq(2, entries.size(), "baseline + latest");
        HookTestSupport.eq("old", HookTestSupport.text(entries.get(0).data()), "baseline is the old value");
        HookTestSupport.eq("new", HookTestSupport.text(entries.get(1).data()), "latest is the new value");

        // 2. Target absent on disk: no COW, baseline is a tombstone.
        runtime.captureChunkWrite(folder, 16, 0, HookTestSupport.tag("fresh", 3), () -> null);
        List<Checkpoint.ChangeSet.Entry> fresh = HookTestSupport.entries(runtime);
        Checkpoint.ChangeSet.Entry absentBaseline = fresh.get(2);
        HookTestSupport.check(absentBaseline.absent(), "missing old value records an absent baseline");
        HookTestSupport.eq("fresh", HookTestSupport.text(fresh.get(3).data()), "new value still captured");

        // 3. Repeated writes keep the first baseline and only move the latest.
        runtime.captureChunkWrite(folder, 0, 0, HookTestSupport.tag("newer", 4), () -> oldTag);
        List<Checkpoint.ChangeSet.Entry> repeated = HookTestSupport.entries(runtime);
        HookTestSupport.eq(4, repeated.size(), "no duplicate baseline");
        HookTestSupport.eq("old", HookTestSupport.text(repeated.get(0).data()), "baseline unchanged");
        HookTestSupport.eq("newer", HookTestSupport.text(repeated.get(1).data()), "latest moved");

        // 4. COW failure marks the snapshot damaged and blocks a restore.
        runtime.captureChunkWrite(folder, 32, 0, HookTestSupport.tag("doomed", 5), () -> {
            throw new IllegalStateException("simulated read failure");
        });
        Snapshot damaged = runtime.createSnapshot("test", "cow failure");
        HookTestSupport.check(damaged.damaged(), "snapshot is flagged damaged");
        HookTestSupport.expectIOException(() -> runtime.prepareRestore(damaged.slot()), "damaged",
                "damaged snapshot cannot be restored");
    }
}
