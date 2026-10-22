package io.github.linkfgfgui.minimalbackup.server;

import java.nio.file.Path;
import java.util.function.BiConsumer;

/**
 * Client-side cover screenshot hook. Null on a dedicated server: covers are only produced by the
 * host client in single-player/LAN, never by a server.
 */
public final class ThumbnailBridge {
    public static volatile BiConsumer<String, Path> capture;

    private ThumbnailBridge() {
    }

    public static void capture(String slot, Path snapshotRoot) {
        BiConsumer<String, Path> current = capture;
        if (current != null) {
            current.accept(slot, snapshotRoot);
        }
    }
}
