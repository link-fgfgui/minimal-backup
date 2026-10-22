package io.github.linkfgfgui.minimalbackup.mixin;

import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches the connection behind a player listener on Fabric.
 *
 * <p>NeoForge exposes {@code getConnection()}; vanilla does not, and since 1.20.2 the field lives
 * on {@code ServerCommonPacketListenerImpl}. Mixin interfaces are inherited by subclasses, so a
 * {@code ServerGamePacketListenerImpl} can be cast to this interface. Keep it accessor-only: a
 * static helper would make Mixin treat it as a regular interface mixin and reject the class target.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public interface ServerCommonPacketListenerAccess {
    @Accessor("connection")
    Connection minimalbackup$connection();
}
