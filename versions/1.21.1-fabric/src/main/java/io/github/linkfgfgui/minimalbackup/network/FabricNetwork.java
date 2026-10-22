package io.github.linkfgfgui.minimalbackup.network;

import io.github.linkfgfgui.minimalbackup.server.BackupService;
import io.github.linkfgfgui.minimalbackup.server.RestoreHandoff;
import io.github.linkfgfgui.minimalbackup.server.UiBridge;
import io.github.linkfgfgui.minimalbackup.ui.BackupRequest;
import io.github.linkfgfgui.minimalbackup.ui.BackupResponse;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fabric registration of the service protocol; commands and packets share
 * {@link BackupService}, exactly like the NeoForge node.
 */
public final class FabricNetwork {
    private FabricNetwork() {
    }

    /** Common side: payload types and the server receiver. */
    public static void registerCommon() {
        PayloadTypeRegistry.playC2S().register(BackupRequestPayload.TYPE,
                BackupRequestPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(BackupResponsePayload.TYPE,
                BackupResponsePayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(RestoreSignalPayload.TYPE,
                RestoreSignalPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(RestoreReadyPayload.TYPE,
                RestoreReadyPayload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(BackupRequestPayload.TYPE,
                (payload, context) -> context.server().execute(() -> {
                    ServerPlayer player = context.player();
                    BackupRequest request = payload.request();
                    if (!BackupService.isAllowed(player, request.action())) {
                        player.connection.disconnect(
                                Component.literal(BackupService.PERMISSION_MESSAGE));
                        return;
                    }
                    BackupResponse response = request.action() == BackupRequest.Action.CREATE
                            ? BackupService.create(player.getServer(), request.name(),
                                    BackupService.identity(player))
                            : BackupService.handle(player.getServer(), request);
                    ServerPlayNetworking.send(player, new BackupResponsePayload(response));
                }));
        RestoreHandoff.handoff = (server, player, slot) -> {
            ServerPlayNetworking.send(player, new RestoreSignalPayload(slot));
            return true;
        };
    }

    /** Client side: S2C receivers. */
    public static void registerClient() {
        ClientPlayNetworking.registerGlobalReceiver(BackupResponsePayload.TYPE,
                (payload, context) -> context.client().execute(
                        () -> UiBridge.acceptResponse(payload.response())));
        ClientPlayNetworking.registerGlobalReceiver(RestoreSignalPayload.TYPE,
                (payload, context) -> context.client().execute(
                        () -> UiBridge.acceptRestoreSignal(payload.slot())));
        ClientPlayNetworking.registerGlobalReceiver(RestoreReadyPayload.TYPE,
                (payload, context) -> context.client().execute(
                        () -> UiBridge.acceptRestoreReady(payload.slot())));
    }

    /** Send one service request to the server. */
    public static void sendRequest(BackupRequest request) {
        ClientPlayNetworking.send(new BackupRequestPayload(request));
    }
}
