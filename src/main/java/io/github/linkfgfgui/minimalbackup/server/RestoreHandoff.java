package io.github.linkfgfgui.minimalbackup.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Platform hook for the restore restart.
 *
 * <p>Loaders with networking install a handoff that sends {@code RestoreSignalPayload} (hold) and
 * {@code RestoreReadyPayload} (reconnect). Loaders that can also replace the server instance
 * install {@link #restarter}; the others keep the old stop-and-reconnect behaviour.
 */
public final class RestoreHandoff {
    /** Installed by a loader; returns false when the player must be disconnected instead. */
    public interface Handoff {
        boolean signal(MinecraftServer server, ServerPlayer player, String slot);
    }

    /** Installed by a loader that can start a new server instance in the same process. */
    public interface Restarter {
        boolean restart(MinecraftServer server);
    }

    public static volatile Handoff handoff;
    public static volatile Restarter restarter;

    private RestoreHandoff() {
    }

    public static boolean signal(MinecraftServer server, ServerPlayer player, String slot) {
        Handoff current = handoff;
        return current != null && current.signal(server, player, slot);
    }

    public static boolean restart(MinecraftServer server) {
        Restarter current = restarter;
        return current != null && current.restart(server);
    }
}
