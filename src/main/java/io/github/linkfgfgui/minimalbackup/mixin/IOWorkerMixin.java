package io.github.linkfgfgui.minimalbackup.mixin;

import io.github.linkfgfgui.minimalbackup.engine.Log;
import io.github.linkfgfgui.minimalbackup.engine.Resolver;
import io.github.linkfgfgui.minimalbackup.server.BackupHooks;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(IOWorker.class)
public abstract class IOWorkerMixin {
    @Shadow
    @Final
    private RegionFileStorage storage;

    private Path minimalbackup$folder() {
        return ((RegionFileStorageAccess) (Object) this.storage).minimalbackup$folder();
    }

    @Inject(method = "loadAsync", at = @At("HEAD"), cancellable = true)
    private void minimalbackup$loadAsync(ChunkPos chunkPos,
            CallbackInfoReturnable<CompletableFuture<Optional<CompoundTag>>> cir) {
        Resolver.Result result = BackupHooks.resolveChunk(minimalbackup$folder(), chunkPos.x, chunkPos.z);
        switch (result.kind()) {
            case PRESENT -> {
                try {
                    CompoundTag tag = BackupHooks.tag(result);
                    cir.setReturnValue(CompletableFuture.completedFuture(Optional.of(tag)));
                } catch (IOException e) {
                    Log.LOGGER.error("cannot decode snapshot chunk {}: {}", chunkPos, e.toString());
                }
            }
            case ABSENT -> cir.setReturnValue(CompletableFuture.completedFuture(Optional.empty()));
            case PASSTHROUGH -> {
            }
        }
    }

    @Inject(method = "scanChunk", at = @At("HEAD"), cancellable = true)
    private void minimalbackup$scanChunk(ChunkPos chunkPos, StreamTagVisitor visitor,
            CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        Resolver.Result result = BackupHooks.resolveChunk(minimalbackup$folder(), chunkPos.x, chunkPos.z);
        switch (result.kind()) {
            case PRESENT -> {
                try {
                    BackupHooks.tag(result).acceptAsRoot(visitor);
                } catch (IOException e) {
                    Log.LOGGER.error("cannot decode snapshot chunk {}: {}", chunkPos, e.toString());
                }
                cir.setReturnValue(CompletableFuture.completedFuture(null));
            }
            case ABSENT -> cir.setReturnValue(CompletableFuture.completedFuture(null));
            case PASSTHROUGH -> {
            }
        }
    }
}
