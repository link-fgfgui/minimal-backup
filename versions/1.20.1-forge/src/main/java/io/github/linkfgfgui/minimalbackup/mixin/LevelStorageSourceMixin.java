package io.github.linkfgfgui.minimalbackup.mixin;

import io.github.linkfgfgui.minimalbackup.engine.Log;
import io.github.linkfgfgui.minimalbackup.engine.Resolver;
import io.github.linkfgfgui.minimalbackup.server.BackupHooks;
import io.github.linkfgfgui.minimalbackup.server.BackupRuntime;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BiFunction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import com.mojang.datafixers.DataFixer;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelStorageSource.class)
public abstract class LevelStorageSourceMixin {
    @Shadow
    @Final
    private DataFixer fixerUpper;

    @Inject(method = "readLevelData", at = @At("HEAD"), cancellable = true)
    private void minimalbackup$readLevelData(LevelStorageSource.LevelDirectory levelDirectory,
            BiFunction<Path, DataFixer, ?> reader, CallbackInfoReturnable<Object> cir) {
        Path file = levelDirectory.dataFile();
        if (file == null || file.getFileName() == null
                || !"level.dat".equals(file.getFileName().toString())) {
            return;
        }
        if (BackupRuntime.get() == null) {
            try {
                BackupRuntime.init(file.getParent());
            } catch (IOException e) {
                Log.LOGGER.error("cannot initialise backup runtime: {}", e.toString());
                return;
            }
        }
        Resolver.Result result = BackupHooks.resolveFile(file);
        switch (result.kind()) {
            case PRESENT -> {
                Path temp = null;
                try {
                    CompoundTag tag = BackupHooks.tag(result);
                    temp = Files.createTempFile(levelDirectory.path(), "minimalbackup-level-", ".dat");
                    NbtIo.writeCompressed(tag, temp.toFile());
                    cir.setReturnValue(reader.apply(temp, fixerUpper));
                } catch (IOException e) {
                    Log.LOGGER.error("cannot decode snapshot level.dat: {}", e.toString());
                } finally {
                    if (temp != null) {
                        try {
                            Files.deleteIfExists(temp);
                        } catch (IOException ignored) {
                        }
                    }
                }
            }
            case ABSENT -> cir.setReturnValue(null);
            case PASSTHROUGH -> {
            }
        }
    }
}
