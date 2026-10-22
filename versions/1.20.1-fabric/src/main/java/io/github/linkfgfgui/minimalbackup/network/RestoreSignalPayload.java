package io.github.linkfgfgui.minimalbackup.network;

import io.github.linkfgfgui.minimalbackup.MinimalBackup;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C: a restore has been armed. On nodes without an in-process restart the server stops right
 * after this packet, so it is the client's cue to show the restore screen and reconnect once the
 * connection drops.
 *
 * <p>1.20.1 has no {@code CustomPacketPayload}; the message is a plain record with an explicit
 * codec.
 */
public record RestoreSignalPayload(String slot) {
    public static final ResourceLocation ID =
            new ResourceLocation(MinimalBackup.MOD_ID, "restore_signal");

    public static RestoreSignalPayload decode(FriendlyByteBuf buf) {
        return new RestoreSignalPayload(buf.readUtf(64));
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(this.slot, 64);
    }
}
