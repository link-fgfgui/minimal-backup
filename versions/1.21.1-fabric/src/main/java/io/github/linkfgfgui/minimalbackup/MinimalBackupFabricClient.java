package io.github.linkfgfgui.minimalbackup;

import io.github.linkfgfgui.minimalbackup.client.ClientOptions;
import io.github.linkfgfgui.minimalbackup.client.ClientTasks;
import io.github.linkfgfgui.minimalbackup.client.RestoreReconnect;
import io.github.linkfgfgui.minimalbackup.client.ThumbnailCapture;
import io.github.linkfgfgui.minimalbackup.client.TreeUi;
import io.github.linkfgfgui.minimalbackup.network.FabricNetwork;
import io.github.linkfgfgui.minimalbackup.server.UiBridge;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

/** Client-only setup: F9 UILib time tree and the restore re-entry hooks. */
public final class MinimalBackupFabricClient implements ClientModInitializer {
    private static final boolean UI_LIB = FabricLoader.getInstance().isModLoaded("uilib");
    private static KeyMapping timeTree;

    @Override
    public void onInitializeClient() {
        ThumbnailCapture.install();
        FabricNetwork.registerClient();
        UiBridge.requestSender = FabricNetwork::sendRequest;
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
                .openWorld(id, () -> Minecraft.getInstance().setScreen(new TitleScreen()));
        RestoreReconnect.serverConnector = data -> {
            Minecraft client = Minecraft.getInstance();
            ConnectScreen.startConnecting(new TitleScreen(), client,
                    ServerAddress.parseString(data.ip), data, false, null);
        };
        com.mojang.blaze3d.platform.InputConstants.Key key = ClientOptions.defaultTimeTreeKey();
        timeTree = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.minimalbackup.time_tree", key.getType(), key.getValue(),
                "key.categories.minimalbackup"));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (timeTree != null && timeTree.consumeClick()) {
                UiBridge.open();
            }
            ClientTasks.tick();
            RestoreReconnect.tick();
        });
    }
}
