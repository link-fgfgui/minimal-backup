package io.github.linkfgfgui.minimalbackup.mixin;

import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets {@code RestoreHold} swap the packet listener to a discard handler while holding. */
@Mixin(Connection.class)
public interface ConnectionAccess {
    @Accessor("packetListener")
    void minimalbackup$setListener(PacketListener listener);
}
