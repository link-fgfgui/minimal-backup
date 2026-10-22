package io.github.linkfgfgui.minimalbackup.network;

import io.github.linkfgfgui.minimalbackup.server.BackupService;
import io.github.linkfgfgui.minimalbackup.server.RestoreHandoff;
import io.github.linkfgfgui.minimalbackup.ui.BackupRequest;
import io.github.linkfgfgui.minimalbackup.ui.BackupResponse;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;

/**
 * Fabric 1.20.1 transport for the restore signal. {@code ServerPlayNetworking.send} refuses
 * channels the client did not announce, so the handoff reports "not delivered" and the caller
 * falls back to the plain disconnect.
 */
public final class FabricNetwork {
    private FabricNetwork() {
    }

    public static void registerCommon() {
        ServerPlayNetworking.registerGlobalReceiver(BackupRequestMessage.ID,
                (server, player, handler, buf, responseSender) -> {
                    BackupRequestMessage message = BackupRequestMessage.decode(buf);
                    server.execute(() -> {
                        if (!player.hasPermissions(2)) {
                            player.connection.disconnect(Component.literal(
                                    "Minimal Backup: permission level 2 required"));
                            return;
                        }
                        BackupRequest request = message.request();
                        BackupResponse response = request.action() == BackupRequest.Action.CREATE
                                ? BackupService.create(server, request.name(),
                                        BackupService.identity(player))
                                : BackupService.handle(server, request);
                        FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
                        new BackupResponseMessage(response).encode(out);
                        ServerPlayNetworking.send(player, BackupResponseMessage.ID, out);
                    });
                });
        RestoreHandoff.handoff = (server, player, slot) -> {
            if (!ServerPlayNetworking.canSend(player, RestoreSignalPayload.ID)) {
                return false;
            }
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            new RestoreSignalPayload(slot).encode(buf);
            ServerPlayNetworking.send(player, RestoreSignalPayload.ID, buf);
            return true;
        };
    }
}
