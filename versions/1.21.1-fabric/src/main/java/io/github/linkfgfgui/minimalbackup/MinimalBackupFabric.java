package io.github.linkfgfgui.minimalbackup;

import io.github.linkfgfgui.minimalbackup.engine.Log;
import io.github.linkfgfgui.minimalbackup.server.BackupCommand;
import io.github.linkfgfgui.minimalbackup.config.ConfigHolder;
import io.github.linkfgfgui.minimalbackup.config.NeoForgeConfig;
import io.github.linkfgfgui.minimalbackup.mixin.ConnectionAccess;
import io.github.linkfgfgui.minimalbackup.mixin.ServerCommonPacketListenerAccess;
import io.github.linkfgfgui.minimalbackup.network.RestoreReadyPayload;
import io.github.linkfgfgui.minimalbackup.server.BackupRuntime;
import io.github.linkfgfgui.minimalbackup.server.RestoreHandoff;
import io.github.linkfgfgui.minimalbackup.server.RestoreHold;
import io.github.linkfgfgui.minimalbackup.network.FabricNetwork;
import java.io.IOException;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import fuzs.forgeconfigapiport.fabric.api.neoforge.v4.NeoForgeConfigRegistry;
import fuzs.forgeconfigapiport.fabric.api.neoforge.v4.NeoForgeModConfigEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.neoforged.fml.config.ModConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

public final class MinimalBackupFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        MinimalBackup.init(FabricLoader.getInstance().getGameDir());
        ConfigHolder.refresher(NeoForgeConfig::reload);
        NeoForgeConfigRegistry.INSTANCE.register(MinimalBackup.MOD_ID, ModConfig.Type.SERVER,
                NeoForgeConfig.SERVER);
        NeoForgeConfigRegistry.INSTANCE.register(MinimalBackup.MOD_ID, ModConfig.Type.CLIENT,
                NeoForgeConfig.CLIENT);
        NeoForgeModConfigEvents.loading(MinimalBackup.MOD_ID)
                .register(config -> NeoForgeConfig.reload());
        NeoForgeModConfigEvents.reloading(MinimalBackup.MOD_ID)
                .register(config -> NeoForgeConfig.reload());
        ServerLifecycleEvents.SERVER_STARTING.register(MinimalBackupFabric::onServerStarting);
        ServerLifecycleEvents.SERVER_STARTED.register(MinimalBackupFabric::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(MinimalBackupFabric::onServerStopping);
        ServerLifecycleEvents.SERVER_STOPPED.register(MinimalBackupFabric::onServerStopped);
        ServerTickEvents.END_SERVER_TICK.register(MinimalBackupFabric::onServerTick);
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registry, environment) -> BackupCommand.register(dispatcher));
        FabricNetwork.registerCommon();
        RestoreHandoff.restarter = FabricRestart::schedule;
        RestoreHold.connectionGetter = player ->
                ((ServerCommonPacketListenerAccess) player.connection).minimalbackup$connection();
        RestoreHold.listenerSetter = (connection, listener) ->
                ((ConnectionAccess) connection).minimalbackup$setListener(listener);
        RestoreHold.heartbeatSender = connection ->
                connection.send(new ClientboundKeepAlivePacket(System.nanoTime()));
        RestoreHold.readySender = connection ->
                connection.send(new ClientboundCustomPayloadPacket(
                        new RestoreReadyPayload(RestoreHold.slot())));
    }

    private static void onServerStarting(MinecraftServer server) {
        NeoForgeConfig.reload();
        try {
            BackupRuntime.init(server.getWorldPath(LevelResource.ROOT));
        } catch (IOException e) {
            Log.LOGGER.error("cannot initialise backup runtime: {}", e.toString());
        }
    }

    private static void onServerStarted(MinecraftServer server) {
        BackupRuntime runtime = BackupRuntime.get();
        if (runtime == null) {
            return;
        }
        if (!runtime.completeRestore(server)) {
            Log.LOGGER.error("restore failed the integrity gate; stopping to keep the old world");
            RestoreHold.close();
            FabricRestart.started();
            server.halt(false);
            return;
        }
        runtime.maybeFullBaseline(server);
        Log.LOGGER.info("backup runtime ready: {}", runtime.statusText());
        FabricRestart.started();
        RestoreHold.ready();
    }

    private static void onServerTick(MinecraftServer server) {
        BackupRuntime runtime = BackupRuntime.get();
        if (runtime != null) {
            runtime.tick();
        }
    }

    private static void onServerStopped(MinecraftServer server) {
        BackupRuntime runtime = BackupRuntime.get();
        if (runtime != null) {
            runtime.onServerStopped();
        }
    }

    private static void onServerStopping(MinecraftServer server) {
        BackupRuntime runtime = BackupRuntime.get();
        if (runtime != null) {
            runtime.onServerStopping();
        }
    }
}
