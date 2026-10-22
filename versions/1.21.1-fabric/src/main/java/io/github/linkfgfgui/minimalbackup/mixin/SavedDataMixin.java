package io.github.linkfgfgui.minimalbackup.mixin;

import io.github.linkfgfgui.minimalbackup.server.BackupHooks;
import java.io.File;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.saveddata.SavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SavedData.class)
public abstract class SavedDataMixin {
    @Inject(method = "save(Ljava/io/File;Lnet/minecraft/core/HolderLookup$Provider;)V", at = @At("HEAD"), cancellable = true)
    private void minimalbackup$captureSave(File file, HolderLookup.Provider provider, CallbackInfo ci) {
        if (BackupHooks.discardWrites()) {
            ci.cancel();
            return;
        }
        SavedData self = (SavedData) (Object) this;
        if (!self.isDirty()) {
            return;
        }
        CompoundTag tag = new CompoundTag();
        tag.put("data", self.save(new CompoundTag(), provider));
        NbtUtils.addCurrentDataVersion(tag);
        BackupHooks.onFileWrite(file.toPath(), tag, BackupHooks.compressedFileReader(file.toPath()));
    }
}
