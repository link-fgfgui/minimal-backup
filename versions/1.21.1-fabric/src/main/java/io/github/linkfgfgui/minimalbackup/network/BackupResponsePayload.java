package io.github.linkfgfgui.minimalbackup.network;

import io.github.linkfgfgui.minimalbackup.ui.BackupResponse;
import io.github.linkfgfgui.minimalbackup.ui.UiCodec;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import java.io.IOException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** S2C service response. */
public record BackupResponsePayload(BackupResponse response) implements CustomPacketPayload {
    public static final Type<BackupResponsePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("minimalbackup", "backup_response"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BackupResponsePayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public BackupResponsePayload decode(RegistryFriendlyByteBuf buf) {
                    try {
                        return new BackupResponsePayload(
                                UiCodec.readResponse(FriendlyCodec.reader(buf)));
                    } catch (IOException e) {
                        throw new DecoderException(e);
                    }
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, BackupResponsePayload payload) {
                    try {
                        UiCodec.writeResponse(FriendlyCodec.writer(buf), payload.response());
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
