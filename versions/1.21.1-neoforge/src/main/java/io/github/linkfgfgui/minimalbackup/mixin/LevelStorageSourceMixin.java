package io.github.linkfgfgui.minimalbackup.mixin;

import io.github.linkfgfgui.minimalbackup.engine.Log;
import io.github.linkfgfgui.minimalbackup.engine.Resolver;
import io.github.linkfgfgui.minimalbackup.server.BackupHooks;
import io.github.linkfgfgui.minimalbackup.server.BackupRuntime;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelStorageSource.class)
public abstract class LevelStorageSourceMixin {
    @Inject(method = "readLevelDataTagRaw", at = @At("HEAD"), cancellable = true)
    private static void minimalbackup$readLevelData(Path path, CallbackInfoReturnable<CompoundTag> cir)
            throws IOException {
        if (path == null || path.getFileName() == null
                || !"level.dat".equals(path.getFileName().toString())) {
            return;
        }
        if (BackupRuntime.get() == null) {
            try {
                BackupRuntime.init(path.getParent());
            } catch (IOException e) {
                Log.LOGGER.error("cannot initialise backup runtime: {}", e.toString());
                return;
            }
        }
        Resolver.Result result = BackupHooks.resolveFile(path);
        switch (result.kind()) {
            case PRESENT -> {
                try {
                    cir.setReturnValue(BackupHooks.tag(result));
                } catch (IOException e) {
                    Log.LOGGER.error("cannot decode snapshot level.dat: {}", e.toString());
                }
            }
            case ABSENT -> throw new IOException("level.dat is absent in the target snapshot");
            case PASSTHROUGH -> {
            }
        }
    }
}
