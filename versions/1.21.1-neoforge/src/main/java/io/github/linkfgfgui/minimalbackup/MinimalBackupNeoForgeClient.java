package io.github.linkfgfgui.minimalbackup;

import io.github.linkfgfgui.minimalbackup.client.ClientOptions;
import io.github.linkfgfgui.minimalbackup.client.ClientTasks;
import io.github.linkfgfgui.minimalbackup.client.RestoreReconnect;
import io.github.linkfgfgui.minimalbackup.client.ThumbnailCapture;
import io.github.linkfgfgui.minimalbackup.client.TreeUi;
import io.github.linkfgfgui.minimalbackup.network.BackupRequestPayload;
import io.github.linkfgfgui.minimalbackup.server.UiBridge;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Client-only setup: F9 time tree (UILib) and the restore re-entry hooks. */
@EventBusSubscriber(modid = MinimalBackup.MOD_ID, value = Dist.CLIENT,
        bus = EventBusSubscriber.Bus.MOD)
public final class MinimalBackupNeoForgeClient {
    private static final boolean UI_LIB = ModList.get().isLoaded("uilib");
    private static KeyMapping timeTree;

    private MinimalBackupNeoForgeClient() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        ThumbnailCapture.install();
        UiBridge.requestSender = request ->
                PacketDistributor.sendToServer(new BackupRequestPayload(request));
        UiBridge.restoreSignal = RestoreReconnect::arm;
        UiBridge.restoreReady = RestoreReconnect::ready;
        UiBridge.responseConsumer = response -> {
            if (!response.ok()) {
                RestoreReconnect.cancel();
            }
            if (UI_LIB) {
                TreeUi.accept(response);
            }
        };
        RestoreReconnect.worldOpener = id -> Minecraft.getInstance().createWorldOpenFlows()
                .openWorld(id, () -> Minecraft.getInstance().setScreen(new TitleScreen()));
        RestoreReconnect.serverConnector = data -> {
            Minecraft client = Minecraft.getInstance();
            ConnectScreen.startConnecting(new TitleScreen(), client,
                    ServerAddress.parseString(data.ip), data, false, null);
        };
        // UILib is optional: without it the client shows a chat hint and commands still work.
        UiBridge.opener = () -> Minecraft.getInstance()
                .execute(MinimalBackupNeoForgeClient::openScreen);
    }

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        com.mojang.blaze3d.platform.InputConstants.Key key = ClientOptions.defaultTimeTreeKey();
        timeTree = new KeyMapping("key.minimalbackup.time_tree", key.getType(), key.getValue(),
                "key.categories.minimalbackup");
        event.register(timeTree);
    }

    private static void openScreen() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        if (!UI_LIB) {
            client.player.displayClientMessage(
                    Component.translatable("minimalbackup.ui.no_uilib"), false);
            return;
        }
        client.setScreen(TreeUi.create());
    }

    @EventBusSubscriber(modid = MinimalBackup.MOD_ID, value = Dist.CLIENT,
            bus = EventBusSubscriber.Bus.GAME)
    public static final class Ticks {
        private Ticks() {
        }

        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            while (timeTree != null && timeTree.consumeClick()) {
                openScreen();
            }
            ClientTasks.tick();
            RestoreReconnect.tick();
        }
    }
}
