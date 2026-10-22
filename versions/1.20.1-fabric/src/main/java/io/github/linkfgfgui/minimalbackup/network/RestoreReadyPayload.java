package io.github.linkfgfgui.minimalbackup.network;

import io.github.linkfgfgui.minimalbackup.MinimalBackup;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C: the replacement server instance is up and the client should reconnect.
 *
 * <p>1.20.1 has no {@code CustomPacketPayload}; the message is a plain record with an explicit
 * codec, like {@link RestoreSignalPayload}.
 */
public record RestoreReadyPayload(String slot) {
    public static final ResourceLocation ID =
            new ResourceLocation(MinimalBackup.MOD_ID, "restore_ready");

    public static RestoreReadyPayload decode(FriendlyByteBuf buf) {
        return new RestoreReadyPayload(buf.readUtf(64));
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(this.slot, 64);
    }
}
