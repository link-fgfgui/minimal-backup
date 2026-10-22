package io.github.linkfgfgui.minimalbackup.network;

import io.github.linkfgfgui.minimalbackup.MinimalBackup;
import io.github.linkfgfgui.minimalbackup.ui.BackupRequest;
import io.github.linkfgfgui.minimalbackup.ui.UiCodec;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import java.io.IOException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/** C2S service request (1.20.1 has no {@code CustomPacketPayload}). */
public record BackupRequestMessage(BackupRequest request) {
    public static final ResourceLocation ID =
            new ResourceLocation(MinimalBackup.MOD_ID, "backup_request");

    public static BackupRequestMessage decode(FriendlyByteBuf buf) {
        try {
            return new BackupRequestMessage(UiCodec.readRequest(FriendlyCodec.reader(buf)));
        } catch (IOException e) {
            throw new DecoderException(e);
        }
    }

    public void encode(FriendlyByteBuf buf) {
        try {
            UiCodec.writeRequest(FriendlyCodec.writer(buf), this.request);
        } catch (IOException e) {
            throw new EncoderException(e);
        }
    }
}
