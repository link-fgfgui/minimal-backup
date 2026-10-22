package io.github.linkfgfgui.minimalbackup.network;

import io.github.linkfgfgui.minimalbackup.ui.BackupRequest;
import io.github.linkfgfgui.minimalbackup.ui.UiCodec;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import java.io.IOException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** C2S service request. */
public record BackupRequestPayload(BackupRequest request) implements CustomPacketPayload {
    public static final Type<BackupRequestPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("minimalbackup", "backup_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BackupRequestPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public BackupRequestPayload decode(RegistryFriendlyByteBuf buf) {
                    try {
                        return new BackupRequestPayload(UiCodec.readRequest(FriendlyCodec.reader(buf)));
                    } catch (IOException e) {
                        throw new DecoderException(e);
                    }
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, BackupRequestPayload payload) {
                    try {
                        UiCodec.writeRequest(FriendlyCodec.writer(buf), payload.request());
                    } catch (IOException e) {
                        throw new EncoderException(e);
                    }
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
