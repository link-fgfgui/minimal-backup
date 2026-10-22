package io.github.linkfgfgui.minimalbackup;

import io.github.linkfgfgui.minimalbackup.engine.Log;
import io.github.linkfgfgui.minimalbackup.server.BackupCommand;
import io.github.linkfgfgui.minimalbackup.config.ConfigHolder;
import io.github.linkfgfgui.minimalbackup.config.ForgeConfig;
import io.github.linkfgfgui.minimalbackup.mixin.ServerGamePacketListenerAccess;
import io.github.linkfgfgui.minimalbackup.network.FabricNetwork;
import io.github.linkfgfgui.minimalbackup.network.RestoreReadyPayload;
import io.github.linkfgfgui.minimalbackup.server.BackupRuntime;
import io.github.linkfgfgui.minimalbackup.server.RestoreHandoff;
import io.github.linkfgfgui.minimalbackup.server.RestoreHold;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundKeepAlivePacket;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import fuzs.forgeconfigapiport.api.config.v2.ForgeConfigRegistry;
import fuzs.forgeconfigapiport.api.config.v2.ModConfigEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

public final class MinimalBackupFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        MinimalBackup.init(FabricLoader.getInstance().getGameDir());
        ConfigHolder.refresher(ForgeConfig::reload);
        ForgeConfigRegistry.INSTANCE.register(MinimalBackup.MOD_ID, ModConfig.Type.SERVER,
                ForgeConfig.SERVER);
        ForgeConfigRegistry.INSTANCE.register(MinimalBackup.MOD_ID, ModConfig.Type.CLIENT,
                ForgeConfig.CLIENT);
        ModConfigEvents.loading(MinimalBackup.MOD_ID).register(config -> ForgeConfig.reload());
        ModConfigEvents.reloading(MinimalBackup.MOD_ID).register(config -> ForgeConfig.reload());
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
                ((ServerGamePacketListenerAccess) player.connection).minimalbackup$connection();
        RestoreHold.listenerSetter = (connection, listener) -> connection.setListener(listener);
        RestoreHold.heartbeatSender = connection ->
                connection.send(new ClientboundKeepAlivePacket(System.nanoTime()));
        RestoreHold.readySender = connection -> {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            new RestoreReadyPayload(RestoreHold.slot()).encode(buf);
            connection.send(new ClientboundCustomPayloadPacket(RestoreReadyPayload.ID, buf));
        };
    }

    private static void onServerStarting(MinecraftServer server) {
        ForgeConfig.reload();
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
