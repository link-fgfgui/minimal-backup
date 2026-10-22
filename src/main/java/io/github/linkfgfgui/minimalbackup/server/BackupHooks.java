package io.github.linkfgfgui.minimalbackup.server;

import io.github.linkfgfgui.minimalbackup.engine.Resolver;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;

/** Thin, exception-safe entry points used by mixins. */
public final class BackupHooks {
    private BackupHooks() {
    }

    public static void onChunkRead(Path folder, int chunkX, int chunkZ, CompoundTag tag) {
        BackupRuntime runtime = BackupRuntime.get();
        if (runtime != null) {
            runtime.captureChunkReadAt(folder, chunkX, chunkZ, tag);
        }
    }

    public static void onChunkWrite(Path folder, int chunkX, int chunkZ, CompoundTag tag,
                                    Supplier<CompoundTag> oldReader) {
        BackupRuntime runtime = BackupRuntime.get();
        if (runtime != null) {
            runtime.captureChunkWrite(folder, chunkX, chunkZ, tag, oldReader);
        }
    }

    public static void onFileWrite(Path file, CompoundTag tag, Supplier<CompoundTag> oldReader) {
        BackupRuntime runtime = BackupRuntime.get();
        if (runtime != null) {
            runtime.captureFileWrite(file, tag, oldReader);
        }
    }

    /**
     * True while a restore is armed. The write hooks cancel their vanilla write in that window so
     * unloading or saving during the rollback cannot overwrite the disk state being restored from.
     */
    public static boolean discardWrites() {
        BackupRuntime runtime = BackupRuntime.get();
        return runtime != null && runtime.isRestoring();
    }

    /** True when a write would be captured; lets callers skip preparatory reads when disabled. */
    public static boolean captureEnabled() {
        BackupRuntime runtime = BackupRuntime.get();
        return runtime != null && runtime.captureEnabled();
    }

    public static Resolver.Result resolveChunk(Path folder, int chunkX, int chunkZ) {
        BackupRuntime runtime = BackupRuntime.get();
        return runtime == null ? Resolver.Result.passthrough()
                : runtime.resolveChunk(folder, chunkX, chunkZ);
    }

    public static Resolver.Result resolveFile(Path file) {
        BackupRuntime runtime = BackupRuntime.get();
        return runtime == null ? Resolver.Result.passthrough() : runtime.resolveFile(file);
    }

    public static Resolver.Result resolveData(File dataFolder, String name) {
        BackupRuntime runtime = BackupRuntime.get();
        return runtime == null ? Resolver.Result.passthrough()
                : runtime.resolveData(dataFolder.getPath(), name);
    }

    public static CompoundTag tag(Resolver.Result result) throws IOException {
        return NbtCodec.fromBytes(result.data());
    }

    /** COW read wrapper: reads bypass the read-capture hook. */
    public static CompoundTag cowRead(Supplier<CompoundTag> reader) {
        return BackupRuntime.withCowRead(reader);
    }

    public static Supplier<CompoundTag> compressedFileReader(Path file) {
        return () -> {
            try {
                return NbtCodec.readPossiblyCompressed(file);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        };
    }
}
