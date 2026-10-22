package io.github.linkfgfgui.minimalbackup.network;

import io.github.linkfgfgui.minimalbackup.MinimalBackup;
import io.github.linkfgfgui.minimalbackup.ui.BackupResponse;
import io.github.linkfgfgui.minimalbackup.ui.UiCodec;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import java.io.IOException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/** S2C service response (1.20.1 has no {@code CustomPacketPayload}). */
public record BackupResponseMessage(BackupResponse response) {
    public static final ResourceLocation ID =
            new ResourceLocation(MinimalBackup.MOD_ID, "backup_response");

    public static BackupResponseMessage decode(FriendlyByteBuf buf) {
        try {
            return new BackupResponseMessage(UiCodec.readResponse(FriendlyCodec.reader(buf)));
        } catch (IOException e) {
            throw new DecoderException(e);
        }
    }

    public void encode(FriendlyByteBuf buf) {
        try {
            UiCodec.writeResponse(FriendlyCodec.writer(buf), this.response);
        } catch (IOException e) {
            throw new EncoderException(e);
        }
    }
}
