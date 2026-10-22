package io.github.linkfgfgui.minimalbackup.mixin;

import io.github.linkfgfgui.minimalbackup.engine.Log;
import io.github.linkfgfgui.minimalbackup.engine.Resolver;
import io.github.linkfgfgui.minimalbackup.server.BackupHooks;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerDataStorage.class)
public abstract class PlayerDataStorageMixin {
    @Shadow
    @Final
    private File playerDir;

    @Inject(method = "load(Lnet/minecraft/world/entity/player/Player;)Lnet/minecraft/nbt/CompoundTag;",
            at = @At("HEAD"), cancellable = true)
    private void minimalbackup$load(Player player, CallbackInfoReturnable<CompoundTag> cir) {
        File file = new File(playerDir, player.getStringUUID() + ".dat");
        Resolver.Result result = BackupHooks.resolveFile(file.toPath());
        switch (result.kind()) {
            case PRESENT -> {
                try {
                    cir.setReturnValue(BackupHooks.tag(result));
                } catch (IOException e) {
                    Log.LOGGER.error("cannot decode snapshot player data: {}", e.toString());
                }
            }
            case ABSENT -> cir.setReturnValue(null);
            case PASSTHROUGH -> {
            }
        }
    }

    @Inject(method = "save(Lnet/minecraft/world/entity/player/Player;)V",
            at = @At("HEAD"), cancellable = true)
    private void minimalbackup$save(Player player, CallbackInfo ci) {
        if (BackupHooks.discardWrites()) {
            ci.cancel();
            return;
        }
        Path file = new File(playerDir, player.getStringUUID() + ".dat").toPath();
        BackupHooks.onFileWrite(file, player.saveWithoutId(new CompoundTag()),
                BackupHooks.compressedFileReader(file));
    }
}
