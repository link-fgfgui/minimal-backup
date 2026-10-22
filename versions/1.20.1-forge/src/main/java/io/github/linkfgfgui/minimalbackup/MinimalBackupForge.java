package io.github.linkfgfgui.minimalbackup;

import io.github.linkfgfgui.minimalbackup.engine.Log;
import io.github.linkfgfgui.minimalbackup.server.BackupCommand;
import io.github.linkfgfgui.minimalbackup.config.ConfigHolder;
import io.github.linkfgfgui.minimalbackup.config.ForgeConfig;
import io.github.linkfgfgui.minimalbackup.mixin.ServerGamePacketListenerAccess;
import io.github.linkfgfgui.minimalbackup.network.ForgeNetwork;
import io.github.linkfgfgui.minimalbackup.server.BackupRuntime;
import io.github.linkfgfgui.minimalbackup.server.RestoreHandoff;
import io.github.linkfgfgui.minimalbackup.server.RestoreHold;
import java.io.IOException;
import net.minecraft.network.protocol.game.ClientboundKeepAlivePacket;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;

@Mod(MinimalBackup.MOD_ID)
public final class MinimalBackupForge {
    public MinimalBackupForge() {
        MinimalBackup.init(FMLPaths.GAMEDIR.get());
        ForgeNetwork.register();
        ConfigHolder.refresher(ForgeConfig::reload);
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, ForgeConfig.SERVER);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, ForgeConfig.CLIENT);
        FMLJavaModLoadingContext.get().getModEventBus()
                .addListener((ModConfigEvent.Loading event) -> ForgeConfig.reload());
        FMLJavaModLoadingContext.get().getModEventBus()
                .addListener((ModConfigEvent.Reloading event) -> ForgeConfig.reload());
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarting);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopped);
        MinecraftForge.EVENT_BUS.addListener(this::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(this::onRegisterCommands);
        RestoreHandoff.restarter = ForgeRestart::schedule;
        RestoreHold.connectionGetter = player ->
                ((ServerGamePacketListenerAccess) player.connection).minimalbackup$connection();
        RestoreHold.listenerSetter = (connection, listener) -> connection.setListener(listener);
        RestoreHold.heartbeatSender = connection ->
                connection.send(new ClientboundKeepAlivePacket(System.nanoTime()));
        RestoreHold.readyPlayerSender = ForgeNetwork::sendReady;
    }

    private void onServerStarting(ServerStartingEvent event) {
        ForgeConfig.reload();
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
            ForgeRestart.started();
            event.getServer().halt(false);
            return;
        }
        runtime.maybeFullBaseline(event.getServer());
        Log.LOGGER.info("backup runtime ready: {}", runtime.statusText());
        ForgeRestart.started();
        RestoreHold.ready();
    }

    private void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
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
