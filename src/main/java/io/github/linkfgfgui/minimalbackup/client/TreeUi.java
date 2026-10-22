package io.github.linkfgfgui.minimalbackup.client;

import io.github.linkfgfgui.minimalbackup.ui.BackupResponse;
import net.minecraft.client.gui.screens.Screen;

/**
 * Holder for the UILib-only screen. Loaders must only touch this class after they have checked
 * that UILib is installed, so a client without it never resolves {@link TimeTreeScreen} or its
 * UILib superclass.
 */
public final class TreeUi {
    private static TimeTreeScreen current;

    private TreeUi() {
    }

    public static Screen create() {
        current = new TimeTreeScreen();
        return current;
    }

    /**
     * Routes a response to the open tree even while the config screen is on top, so a pending
     * request is not lost when the player changes a setting mid-refresh.
     */
    public static boolean accept(BackupResponse response) {
        TimeTreeScreen screen = current;
        if (screen != null) {
            screen.acceptResponse(response);
            return true;
        }
        return false;
    }

    static void closed(TimeTreeScreen screen) {
        if (current == screen) {
            current = null;
        }
    }
}
