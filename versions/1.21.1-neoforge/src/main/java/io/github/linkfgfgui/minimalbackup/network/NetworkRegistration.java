package io.github.linkfgfgui.minimalbackup.network;

import io.github.linkfgfgui.minimalbackup.MinimalBackup;
import io.github.linkfgfgui.minimalbackup.server.BackupService;
import io.github.linkfgfgui.minimalbackup.server.UiBridge;
import io.github.linkfgfgui.minimalbackup.ui.BackupRequest;
import io.github.linkfgfgui.minimalbackup.ui.BackupResponse;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Registers the service protocol; commands and packets share {@link BackupService}. */
@EventBusSubscriber(modid = MinimalBackup.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class NetworkRegistration {
    private NetworkRegistration() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1").optional();
        registrar.playToServer(BackupRequestPayload.TYPE, BackupRequestPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (!(context.player() instanceof ServerPlayer player)) {
                        return;
                    }
                    if (!player.hasPermissions(2)) {
                        context.disconnect(Component.literal(
                                "Minimal Backup: permission level 2 required"));
                        return;
                    }
                    BackupRequest request = payload.request();
                    BackupResponse response = request.action() == BackupRequest.Action.CREATE
                            ? BackupService.create(player.getServer(), request.name(),
                                    BackupService.identity(player))
                            : BackupService.handle(player.getServer(), request);
                    context.reply(new BackupResponsePayload(response));
                }));
        registrar.playToClient(BackupResponsePayload.TYPE, BackupResponsePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> UiBridge.acceptResponse(payload.response())));
        registrar.playToClient(RestoreSignalPayload.TYPE, RestoreSignalPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> UiBridge.acceptRestoreSignal(payload.slot())));
        registrar.playToClient(RestoreReadyPayload.TYPE, RestoreReadyPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> UiBridge.acceptRestoreReady(payload.slot())));
    }
}
