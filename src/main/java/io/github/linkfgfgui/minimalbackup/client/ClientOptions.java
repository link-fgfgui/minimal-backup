package io.github.linkfgfgui.minimalbackup.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/**
 * Client-side key mapping defaults.
 *
 * <p>The time-tree key is a normal Minecraft key binding: players rebind it in
 * {@code Options -> Controls}. The mod does not keep a second copy in its own config.
 */
public final class ClientOptions {
    private ClientOptions() {
    }

    /** Default key for the time tree; the player can rebind it in the vanilla controls screen. */
    public static InputConstants.Key defaultTimeTreeKey() {
        return InputConstants.getKey(GLFW.GLFW_KEY_F9, 0);
    }

    /** The quick-create binding starts unbound. */
    public static KeyMapping createBackupMapping() {
        InputConstants.Key key = InputConstants.UNKNOWN;
        return new KeyMapping("key.minimalbackup.create_backup",
                key.getType(), key.getValue(), "key.categories.minimalbackup");
    }

    /** Snapshot name used by the create dialog and the quick-create binding. */
    public static String defaultBackupName() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }
}
