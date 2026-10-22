package io.github.linkfgfgui.minimalbackup.network;

import io.github.linkfgfgui.minimalbackup.MinimalBackup;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C: a restore has been armed and the server is holding the connection. The client shows the
 * restore screen and waits for {@link RestoreReadyPayload}; the packet carries only the slot for
 * logging.
 */
public record RestoreSignalPayload(String slot) implements CustomPacketPayload {
    public static final Type<RestoreSignalPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MinimalBackup.MOD_ID, "restore_signal"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RestoreSignalPayload> STREAM_CODEC =
            StreamCodec.composite(ByteBufCodecs.STRING_UTF8, RestoreSignalPayload::slot,
                    RestoreSignalPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
