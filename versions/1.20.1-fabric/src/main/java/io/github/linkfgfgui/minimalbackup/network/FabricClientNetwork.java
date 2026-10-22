package io.github.linkfgfgui.minimalbackup.network;

import io.github.linkfgfgui.minimalbackup.server.UiBridge;
import io.github.linkfgfgui.minimalbackup.ui.BackupRequest;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;

/** Client half of the restore signal on Fabric 1.20.1. */
public final class FabricClientNetwork {
    private FabricClientNetwork() {
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(RestoreSignalPayload.ID,
                (client, handler, buf, responseSender) -> {
                    RestoreSignalPayload payload = RestoreSignalPayload.decode(buf);
                    client.execute(() -> UiBridge.acceptRestoreSignal(payload.slot()));
                });
        ClientPlayNetworking.registerGlobalReceiver(RestoreReadyPayload.ID,
                (client, handler, buf, responseSender) -> {
                    RestoreReadyPayload payload = RestoreReadyPayload.decode(buf);
                    client.execute(() -> UiBridge.acceptRestoreReady(payload.slot()));
                });
        ClientPlayNetworking.registerGlobalReceiver(BackupResponseMessage.ID,
                (client, handler, buf, responseSender) -> {
                    BackupResponseMessage message = BackupResponseMessage.decode(buf);
                    client.execute(() -> UiBridge.acceptResponse(message.response()));
                });
    }

    /** Send one service request to the server. */
    public static void sendRequest(BackupRequest request) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        new BackupRequestMessage(request).encode(buf);
        ClientPlayNetworking.send(BackupRequestMessage.ID, buf);
    }
}
