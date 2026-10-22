package io.github.linkfgfgui.minimalbackup.client;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.linkfgfgui.minimalbackup.server.ThumbnailBridge;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * Captures the world view when a snapshot is created on an integrated server. UIs are hidden by
 * the caller for manual creation; automatic snapshots normally have no screen open.
 */
public final class ThumbnailCapture {
    private ThumbnailCapture() {
    }

    public static void install() {
        ThumbnailBridge.capture = ThumbnailCapture::request;
    }

    private static String pendingSlot;
    private static Path pendingRoot;

    private static void request(String slot, Path snapshotRoot) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (client.screen != null) {
                // The GUI is open. The world is still rendered beneath it, but only until the
                // screen draws; capture at the start of the next screen frame instead of
                // closing the screen just for the cover.
                pendingSlot = slot;
                pendingRoot = snapshotRoot;
                return;
            }
            write(slot, snapshotRoot);
        });
    }

    /** Call at the start of a screen frame, before the GUI is drawn. */
    public static void capturePending() {
        String slot = pendingSlot;
        Path root = pendingRoot;
        if (slot == null || root == null) {
            return;
        }
        pendingSlot = null;
        pendingRoot = null;
        write(slot, root);
    }

    private static void write(String slot, Path snapshotRoot) {
        Minecraft client = Minecraft.getInstance();
        NativeImage image = Screenshot.takeScreenshot(client.getMainRenderTarget());
        if (image == null) {
            return;
        }
        try {
            Path dir = snapshotRoot.resolve("thumbnails");
            Files.createDirectories(dir);
            image.writeToFile(dir.resolve(slot + ".png"));
        } catch (IOException ignored) {
            // A failed cover must never affect the backup operation.
        } finally {
            image.close();
        }
    }
}
