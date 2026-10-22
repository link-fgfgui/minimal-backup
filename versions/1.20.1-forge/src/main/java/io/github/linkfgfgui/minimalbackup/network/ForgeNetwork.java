package io.github.linkfgfgui.minimalbackup.network;

import io.github.linkfgfgui.minimalbackup.MinimalBackup;
import io.github.linkfgfgui.minimalbackup.server.BackupService;
import io.github.linkfgfgui.minimalbackup.server.RestoreHandoff;
import io.github.linkfgfgui.minimalbackup.server.UiBridge;
import io.github.linkfgfgui.minimalbackup.ui.BackupRequest;
import io.github.linkfgfgui.minimalbackup.ui.BackupResponse;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Forge 1.20.1 transport for the restore signal. The channel accepts clients that do not have it,
 * so a server with the mod still lets vanilla clients join; such a client simply never gets the
 * signal and falls back to the normal disconnect screen.
 */
public final class ForgeNetwork {
    private static final String PROTOCOL = "1";

    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MinimalBackup.MOD_ID, "main"),
            () -> PROTOCOL,
            NetworkRegistry.acceptMissingOr(PROTOCOL),
            NetworkRegistry.acceptMissingOr(PROTOCOL));

    private ForgeNetwork() {
    }

    public static void register() {
        CHANNEL.messageBuilder(RestoreSignalPayload.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RestoreSignalPayload::encode)
                .decoder(RestoreSignalPayload::decode)
                .consumerMainThread((payload, context) ->
                        UiBridge.acceptRestoreSignal(payload.slot()))
                .add();
        CHANNEL.messageBuilder(RestoreReadyPayload.class, 3, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RestoreReadyPayload::encode)
                .decoder(RestoreReadyPayload::decode)
                .consumerMainThread((payload, context) ->
                        UiBridge.acceptRestoreReady(payload.slot()))
                .add();
        CHANNEL.messageBuilder(BackupRequestMessage.class, 1, NetworkDirection.PLAY_TO_SERVER)
                .encoder(BackupRequestMessage::encode)
                .decoder(BackupRequestMessage::decode)
                .consumerMainThread((message, context) -> {
                    ServerPlayer player = context.get().getSender();
                    if (player == null) {
                        return;
                    }
                    if (!player.hasPermissions(2)) {
                        player.connection.disconnect(Component.literal(
                                "Minimal Backup: permission level 2 required"));
                        return;
                    }
                    BackupRequest request = message.request();
                    BackupResponse response = request.action() == BackupRequest.Action.CREATE
                            ? BackupService.create(player.server, request.name(),
                                    BackupService.identity(player))
                            : BackupService.handle(player.server, request);
                    CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                            new BackupResponseMessage(response));
                })
                .add();
        CHANNEL.messageBuilder(BackupResponseMessage.class, 2, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(BackupResponseMessage::encode)
                .decoder(BackupResponseMessage::decode)
                .consumerMainThread((message, context) ->
                        UiBridge.acceptResponse(message.response()))
                .add();
        RestoreHandoff.handoff = (server, player, slot) -> {
            if (!CHANNEL.isRemotePresent(player.connection.connection)) {
                return false;
            }
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new RestoreSignalPayload(slot));
            return true;
        };
    }

    /** Server entry: tell one held player that the replacement instance is ready. */
    public static void sendReady(ServerPlayer player, String slot) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new RestoreReadyPayload(slot));
    }

    /** Client entry: send one service request to the server. */
    public static void sendRequest(BackupRequest request) {
        CHANNEL.sendToServer(new BackupRequestMessage(request));
    }
}
