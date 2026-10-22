package io.github.linkfgfgui.minimalbackup.mixin;

import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches the connection behind a player listener on 1.20.1.
 *
 * <p>In 1.20.1 the field is still declared on {@code ServerGamePacketListenerImpl} (it moved to
 * {@code ServerCommonPacketListenerImpl} in 1.20.2), so this node needs its own accessor.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public interface ServerGamePacketListenerAccess {
    @Accessor("connection")
    Connection minimalbackup$connection();
}
