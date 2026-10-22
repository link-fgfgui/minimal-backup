package io.github.linkfgfgui.minimalbackup.mixin;

import io.github.linkfgfgui.minimalbackup.engine.Log;
import io.github.linkfgfgui.minimalbackup.server.BackupHooks;
import io.github.linkfgfgui.minimalbackup.server.NbtCodec;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.WorldData;
import org.jetbrains.annotations.Nullable;
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

    private CompoundTag minimalbackup$previousLevelData;
    private boolean minimalbackup$captureLevelData;

    /**
     * 1.20.1 的 saveDataTag 只拿到 WorldData；Forge 的 fml（以及别的 mod 往根上挂的标签）是在
     * 方法体里补的。HEAD 只记旧值，RETURN 读回磁盘上刚写好的文件，捕获的根与磁盘同源。
     */
    @Inject(method = "saveDataTag(Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/level/storage/WorldData;Lnet/minecraft/nbt/CompoundTag;)V",
            at = @At("HEAD"), cancellable = true)
    private void minimalbackup$beforeLevelDataWrite(RegistryAccess registryAccess, WorldData worldData,
                                                    @Nullable CompoundTag singleplayerData, CallbackInfo ci) {
        minimalbackup$captureLevelData = false;
        if (BackupHooks.discardWrites()) {
            ci.cancel();
            return;
        }
        if (!BackupHooks.captureEnabled()) {
            return;
        }
        minimalbackup$previousLevelData = minimalbackup$read(levelDirectory.dataFile());
        minimalbackup$captureLevelData = true;
    }

    @Inject(method = "saveDataTag(Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/level/storage/WorldData;Lnet/minecraft/nbt/CompoundTag;)V",
            at = @At("RETURN"))
    private void minimalbackup$afterLevelDataWrite(RegistryAccess registryAccess, WorldData worldData,
                                                   @Nullable CompoundTag singleplayerData, CallbackInfo ci) {
        if (!minimalbackup$captureLevelData) {
            return;
        }
        minimalbackup$captureLevelData = false;
        CompoundTag previous = minimalbackup$previousLevelData;
        minimalbackup$previousLevelData = null;
        Path file = levelDirectory.dataFile();
        CompoundTag written = minimalbackup$read(file);
        if (written == null) {
            return;
        }
        BackupHooks.onFileWrite(file, written, previous == null ? null : () -> previous);
    }

    private static CompoundTag minimalbackup$read(Path file) {
        try {
            return NbtCodec.readPossiblyCompressed(file);
        } catch (IOException e) {
            Log.LOGGER.error("cannot read level.dat: {}", e.toString());
            return null;
        }
    }
}
