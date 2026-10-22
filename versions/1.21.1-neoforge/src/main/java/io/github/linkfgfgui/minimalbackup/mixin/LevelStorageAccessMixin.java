package io.github.linkfgfgui.minimalbackup.mixin;

import io.github.linkfgfgui.minimalbackup.server.BackupHooks;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelStorageSource.LevelStorageAccess.class)
public abstract class LevelStorageAccessMixin {
    @Shadow
    @Final
    LevelStorageSource.LevelDirectory levelDirectory;

    @Inject(method = "saveLevelData", at = @At("HEAD"), cancellable = true)
    private void minimalbackup$captureLevelData(CompoundTag tag, CallbackInfo ci) {
        if (BackupHooks.discardWrites()) {
            ci.cancel();
            return;
        }
        Path file = levelDirectory.dataFile();
        BackupHooks.onFileWrite(file, tag, BackupHooks.compressedFileReader(file));
    }
}
