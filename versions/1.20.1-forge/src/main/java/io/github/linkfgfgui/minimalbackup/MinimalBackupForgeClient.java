package io.github.linkfgfgui.minimalbackup;

import io.github.linkfgfgui.minimalbackup.client.ClientOptions;
import io.github.linkfgfgui.minimalbackup.client.ClientTasks;
import io.github.linkfgfgui.minimalbackup.client.RestoreReconnect;
import io.github.linkfgfgui.minimalbackup.client.ThumbnailCapture;
import io.github.linkfgfgui.minimalbackup.client.TreeUi;
import io.github.linkfgfgui.minimalbackup.network.ForgeNetwork;
import io.github.linkfgfgui.minimalbackup.server.UiBridge;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/** Client-only setup: F9 UILib time tree and the restore re-entry hooks. */
public final class MinimalBackupForgeClient {
    private static final boolean UI_LIB = ModList.get().isLoaded("uilib");
    private static KeyMapping timeTree;

    private MinimalBackupForgeClient() {
    }

    @Mod.EventBusSubscriber(modid = MinimalBackup.MOD_ID, value = Dist.CLIENT,
            bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModBus {
        private ModBus() {
        }

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            ThumbnailCapture.install();
            UiBridge.requestSender = ForgeNetwork::sendRequest;
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
            UiBridge.opener = () -> Minecraft.getInstance().execute(() -> {
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
            });
            RestoreReconnect.worldOpener = id -> Minecraft.getInstance().createWorldOpenFlows()
                    .loadLevel(new TitleScreen(), id);
            RestoreReconnect.serverConnector = data -> {
                Minecraft client = Minecraft.getInstance();
                ConnectScreen.startConnecting(new TitleScreen(), client,
                        ServerAddress.parseString(data.ip), data, false);
            };
        }

        @SubscribeEvent
        public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
            com.mojang.blaze3d.platform.InputConstants.Key key = ClientOptions.defaultTimeTreeKey();
            timeTree = new KeyMapping("key.minimalbackup.time_tree", key.getType(), key.getValue(),
                    "key.categories.minimalbackup");
            event.register(timeTree);
        }
    }

    @Mod.EventBusSubscriber(modid = MinimalBackup.MOD_ID, value = Dist.CLIENT)
    public static final class ForgeBus {
        private ForgeBus() {
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.END) {
                while (timeTree != null && timeTree.consumeClick()) {
                    UiBridge.open();
                }
                ClientTasks.tick();
                RestoreReconnect.tick();
            }
        }
    }
}
