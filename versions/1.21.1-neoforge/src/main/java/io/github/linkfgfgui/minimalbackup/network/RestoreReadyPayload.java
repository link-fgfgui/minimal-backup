package io.github.linkfgfgui.minimalbackup.network;

import io.github.linkfgfgui.minimalbackup.MinimalBackup;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C: the replacement server instance has loaded the snapshot. The client drops the connection
 * it was holding and logs in again; the packet carries only the slot for logging.
 */
public record RestoreReadyPayload(String slot) implements CustomPacketPayload {
    public static final Type<RestoreReadyPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MinimalBackup.MOD_ID, "restore_ready"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RestoreReadyPayload> STREAM_CODEC =
            StreamCodec.composite(ByteBufCodecs.STRING_UTF8, RestoreReadyPayload::slot,
                    RestoreReadyPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
