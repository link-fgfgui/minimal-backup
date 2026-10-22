package io.github.linkfgfgui.minimalbackup.client;

import com.mojang.blaze3d.platform.InputConstants;
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
}
