package io.github.linkfgfgui.minimalbackup.mixin;

import io.github.linkfgfgui.minimalbackup.engine.Log;
import io.github.linkfgfgui.minimalbackup.engine.Resolver;
import io.github.linkfgfgui.minimalbackup.server.BackupHooks;
import java.io.File;
import java.io.IOException;
import java.util.function.BiFunction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DimensionDataStorage.class)
public abstract class DimensionDataStorageMixin {
    @Shadow
    @Final
    private File dataFolder;

    @Shadow
    @Final
    private HolderLookup.Provider registries;

    @Inject(method = "readSavedData", at = @At("HEAD"), cancellable = true)
    private void minimalbackup$readSavedData(
            BiFunction<CompoundTag, HolderLookup.Provider, ?> deserializer, DataFixTypes type,
            String name, CallbackInfoReturnable<Object> cir) {
        Resolver.Result result = BackupHooks.resolveData(dataFolder, name);
        switch (result.kind()) {
            case PRESENT -> {
                try {
                    CompoundTag tag = BackupHooks.tag(result);
                    cir.setReturnValue(deserializer.apply(tag.getCompound("data"), registries));
                } catch (IOException e) {
                    Log.LOGGER.error("cannot decode snapshot data {}: {}", name, e.toString());
                }
            }
            case ABSENT -> cir.setReturnValue(null);
            case PASSTHROUGH -> {
            }
        }
    }
}
