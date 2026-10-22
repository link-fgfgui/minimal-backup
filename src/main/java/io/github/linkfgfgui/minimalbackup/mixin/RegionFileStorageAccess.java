package io.github.linkfgfgui.minimalbackup.mixin;

import java.nio.file.Path;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RegionFileStorage.class)
public interface RegionFileStorageAccess {
    @Accessor("folder")
    Path minimalbackup$folder();
}
