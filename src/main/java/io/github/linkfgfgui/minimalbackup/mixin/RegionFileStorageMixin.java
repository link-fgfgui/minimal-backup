package io.github.linkfgfgui.minimalbackup.mixin;

import io.github.linkfgfgui.minimalbackup.server.BackupHooks;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RegionFileStorage.class)
public abstract class RegionFileStorageMixin {
    @Shadow
    @Final
    private Path folder;

    @Shadow
    @Nullable
    public abstract CompoundTag read(ChunkPos chunkPos) throws IOException;

    @Inject(method = "write", at = @At("HEAD"), cancellable = true)
    private void minimalbackup$captureWrite(ChunkPos chunkPos, @Nullable CompoundTag tag, CallbackInfo ci) {
        if (BackupHooks.discardWrites()) {
            ci.cancel();
            return;
        }
        Supplier<CompoundTag> oldReader = () -> BackupHooks.cowRead(() -> {
            try {
                return read(chunkPos);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        BackupHooks.onChunkWrite(folder, chunkPos.x, chunkPos.z, tag, oldReader);
    }

    @Inject(method = "read", at = @At("RETURN"))
    private void minimalbackup$captureRead(ChunkPos chunkPos, CallbackInfoReturnable<CompoundTag> cir) {
        BackupHooks.onChunkRead(folder, chunkPos.x, chunkPos.z, cir.getReturnValue());
    }
}
