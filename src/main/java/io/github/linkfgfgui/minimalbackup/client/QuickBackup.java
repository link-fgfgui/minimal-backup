package io.github.linkfgfgui.minimalbackup.client;

import io.github.linkfgfgui.minimalbackup.server.BackupService;
import io.github.linkfgfgui.minimalbackup.server.UiBridge;
import io.github.linkfgfgui.minimalbackup.ui.BackupRequest;
import io.github.linkfgfgui.minimalbackup.ui.BackupResponse;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** One-key snapshot creation for players who do not want to open the time tree. */
public final class QuickBackup {
    /** True while the quick binding waits for its response line. */
    private static boolean pending;

    private QuickBackup() {
    }

    /** Called from the client tick loop when the unbound-by-default key is pressed. */
    public static void create() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.screen != null) {
            return;
        }
        if (!client.player.hasPermissions(BackupService.REQUIRED_PERMISSION)) {
            client.player.displayClientMessage(
                    Component.literal(BackupService.PERMISSION_MESSAGE), false);
            return;
        }
        pending = true;
        UiBridge.sendRequest(new BackupRequest(BackupRequest.Action.CREATE, "",
                ClientOptions.defaultBackupName(), false));
    }

    /** Called when no screen consumed the response; shows the service result in chat. */
    public static void show(BackupResponse response) {
        if (!pending) {
            return;
        }
        pending = false;
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.displayClientMessage(Component.literal(response.message()), false);
        }
    }

    /** The open tree owns the response; do not repeat it in chat later. */
    public static void discard() {
        pending = false;
    }
}
