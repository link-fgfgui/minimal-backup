package io.github.linkfgfgui.minimalbackup.mixin;

import io.github.linkfgfgui.minimalbackup.server.RestoreSwap;
import net.minecraft.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the shared executors alive while a restore replaces the server instance: the new server
 * loads its world through {@code Util.backgroundExecutor()}, which the stopping instance would
 * otherwise shut down.
 */
@Mixin(Util.class)
public abstract class UtilMixin {
    @Inject(method = "shutdownExecutors", at = @At("HEAD"), cancellable = true)
    private static void minimalbackup$keepExecutors(CallbackInfo ci) {
        if (RestoreSwap.swapping) {
            ci.cancel();
        }
    }
}
