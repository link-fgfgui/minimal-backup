package io.github.linkfgfgui.minimalbackup;

import io.github.linkfgfgui.minimalbackup.engine.Log;
import io.github.linkfgfgui.minimalbackup.server.BackupCommand;
import io.github.linkfgfgui.minimalbackup.config.ConfigHolder;
import io.github.linkfgfgui.minimalbackup.config.NeoForgeConfig;
import io.github.linkfgfgui.minimalbackup.mixin.ConnectionAccess;
import io.github.linkfgfgui.minimalbackup.network.RestoreReadyPayload;
import io.github.linkfgfgui.minimalbackup.network.RestoreSignalPayload;
import io.github.linkfgfgui.minimalbackup.server.BackupRuntime;
import io.github.linkfgfgui.minimalbackup.server.RestoreHandoff;
import io.github.linkfgfgui.minimalbackup.server.RestoreHold;
import java.io.IOException;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@Mod(MinimalBackup.MOD_ID)
public final class MinimalBackupNeoForge {
    public MinimalBackupNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        MinimalBackup.init(FMLPaths.GAMEDIR.get());
        ConfigHolder.refresher(NeoForgeConfig::reload);
        modContainer.registerConfig(ModConfig.Type.SERVER, NeoForgeConfig.SERVER);
        modContainer.registerConfig(ModConfig.Type.CLIENT, NeoForgeConfig.CLIENT);
        modEventBus.addListener((ModConfigEvent.Loading event) -> NeoForgeConfig.reload());
        modEventBus.addListener((ModConfigEvent.Reloading event) -> NeoForgeConfig.reload());
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        RestoreHandoff.handoff = (server, player, slot) -> {
            player.connection.send(new RestoreSignalPayload(slot));
            return true;
        };
        RestoreHandoff.restarter = NeoForgeRestart::schedule;
        RestoreHold.connectionGetter = player -> player.connection.getConnection();
        RestoreHold.listenerSetter = (connection, listener) ->
                ((ConnectionAccess) connection).minimalbackup$setListener(listener);
        RestoreHold.heartbeatSender = connection ->
                connection.send(new ClientboundKeepAlivePacket(System.nanoTime()));
        RestoreHold.readySender = connection ->
                connection.send(new ClientboundCustomPayloadPacket(
                        new RestoreReadyPayload(RestoreHold.slot())));
    }

    private void onServerStarting(ServerStartingEvent event) {
        NeoForgeConfig.reload();
        try {
            BackupRuntime.init(event.getServer().getWorldPath(LevelResource.ROOT));
        } catch (IOException e) {
            Log.LOGGER.error("cannot initialise backup runtime: {}", e.toString());
        }
    }

    private void onServerStarted(ServerStartedEvent event) {
        BackupRuntime runtime = BackupRuntime.get();
        if (runtime == null) {
            return;
        }
        if (!runtime.completeRestore(event.getServer())) {
            Log.LOGGER.error("restore failed the integrity gate; stopping to keep the old world");
            RestoreHold.close();
            NeoForgeRestart.started();
            event.getServer().halt(false);
            return;
        }
        runtime.maybeFullBaseline(event.getServer());
        Log.LOGGER.info("backup runtime ready: {}", runtime.statusText());
        NeoForgeRestart.started();
        RestoreHold.ready();
    }

    private void onServerTick(ServerTickEvent.Post event) {
        BackupRuntime runtime = BackupRuntime.get();
        if (runtime != null) {
            runtime.tick();
        }
    }

    private void onServerStopping(ServerStoppingEvent event) {
        BackupRuntime runtime = BackupRuntime.get();
        if (runtime != null) {
            runtime.onServerStopping();
        }
    }

    private void onServerStopped(ServerStoppedEvent event) {
        BackupRuntime runtime = BackupRuntime.get();
        if (runtime != null) {
            runtime.onServerStopped();
        }
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        BackupCommand.register(event.getDispatcher());
    }
}
