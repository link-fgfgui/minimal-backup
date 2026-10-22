package io.github.linkfgfgui.minimalbackup.server;

import io.github.linkfgfgui.minimalbackup.engine.Block;
import io.github.linkfgfgui.minimalbackup.engine.BlockFile;
import io.github.linkfgfgui.minimalbackup.engine.Resolver;
import io.github.linkfgfgui.minimalbackup.engine.Snapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;

/** RestoreGateTest: a restore that served nothing must not look successful. */
public final class TestRestoreGate {
    private TestRestoreGate() {
    }

    public static void run(Path gameDir) throws Exception {
        Path world = HookTestSupport.world(gameDir, "GateWorld");
        BackupRuntime runtime = BackupRuntime.init(world);
        Path folder = world.resolve("region");

        // A snapshot with data for one chunk only.
        runtime.captureChunkWrite(folder, 0, 0, HookTestSupport.tag("only", 1), () -> null);
        Snapshot snapshot = runtime.createSnapshot("test", "gate");
        runtime.arm(snapshot.slot());

        // The snapshot covers chunk (0,0); asking for another chunk is a miss.
        Resolver.Result miss = runtime.resolveChunk(folder, 256, 256);
        HookTestSupport.eq(Resolver.Kind.PASSTHROUGH, miss.kind(), "uncovered chunk passes through");
        HookTestSupport.eq(0L, runtime.coverHits(), "no hits yet");
        HookTestSupport.check(!runtime.restoreServedSnapshot(), "gate reports no coverage");
        HookTestSupport.contains(runtime.restoreVerdict(), "coverHits=0", "verdict names the problem");

        Resolver.Result hit = runtime.resolveChunk(folder, 0, 0);
        HookTestSupport.eq(Resolver.Kind.PRESENT, hit.kind(), "covered chunk is served");
        HookTestSupport.check(runtime.restoreServedSnapshot(), "gate sees the hit");
        HookTestSupport.contains(runtime.restoreVerdict(), "served 1", "verdict counts the hit");
        runtime.disarm();

        // A corrupt block must fail loudly rather than silently serving garbage.
        runtime.captureChunkWrite(folder, 32, 32, HookTestSupport.tag("corrupt", 2), () -> null);
        Snapshot second = runtime.createSnapshot("test", "corrupt");
        Path zpack = world.getParent().getParent().resolve("mbackups").resolve("GateWorld")
                .resolve("minimalbackup_snapshots").resolve("region").resolve("r.1.1.mca.zpack");
        HookTestSupport.check(Files.exists(zpack), "expected block file " + zpack);
        // Flip a byte inside the payload of the newest block: the header still parses, so the
        // resolver reaches the hash check and must refuse the block instead of serving garbage.
        var blocks = BlockFile.loadTable(zpack, -1L)
                .blocksFor(SnapshotKeys.chunkSubTarget(32, 32));
        HookTestSupport.check(!blocks.isEmpty(), "expected a block for chunk 32,32");
        Block newest = blocks.get(blocks.size() - 1);
        byte[] broken = Files.readAllBytes(zpack);
        broken[(int) (newest.offset() + Block.HEADER_SIZE)] ^= 0x5A;
        Files.write(zpack, broken);
        runtime.arm(second.slot());
        Resolver.Result corrupt = runtime.resolveChunk(folder, 32, 32);
        HookTestSupport.eq(Resolver.Kind.PASSTHROUGH, corrupt.kind(), "corrupt block passes through");
        HookTestSupport.check(!runtime.lastError().isEmpty(), "corruption is reported");
        BackupRuntime.VerifyReport brokenReport = runtime.verify(second.slot());
        HookTestSupport.check(brokenReport.failures > 0, "verify reports the corrupt block");
        runtime.disarm();

        BackupRuntime.VerifyReport cleanReport = runtime.verify(snapshot.slot());
        HookTestSupport.eq(0L, cleanReport.failures, "healthy snapshot verifies cleanly");
        HookTestSupport.check(cleanReport.present > 0, "healthy snapshot has visible blocks");

        // Damaged snapshots are rejected before the server is stopped.
        runtime.captureChunkWrite(folder, 64, 64, HookTestSupport.tag("bad", 3), () -> {
            throw new IllegalStateException("simulated");
        });
        Snapshot damaged = runtime.createSnapshot("test", "damaged");
        HookTestSupport.expectIOException(() -> runtime.prepareRestore(damaged.slot()), "damaged",
                "damaged snapshot cannot be armed");
    }
}
