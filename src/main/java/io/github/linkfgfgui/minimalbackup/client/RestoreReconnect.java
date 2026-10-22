package io.github.linkfgfgui.minimalbackup.client;

import io.github.linkfgfgui.minimalbackup.engine.Log;
import io.github.linkfgfgui.minimalbackup.engine.VaultPaths;
import io.github.linkfgfgui.minimalbackup.mixin.ConnectionAccess;
import io.github.linkfgfgui.minimalbackup.server.BackupRuntime;
import io.github.linkfgfgui.minimalbackup.server.RestoreHold;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.chat.Component;

/**
 * Client half of a restore.
 *
 * <p>On a server the old instance keeps the connection open (heartbeat + drain) while it is
 * replaced. The client stays in the world, gets a chat line and the on-screen title overlay, and does nothing
 * until the server sends {@code RestoreReadyPayload}; only then does it drop the old connection and
 * log in again. If the ready packet never arrives the client keeps waiting in the world instead of
 * timing out.
 *
 * <p>Single player uses the same hold: the server removes the local player from its list but leaves
 * the memory connection open, and heartbeats keep the client in the old world while the integrated
 * server stops. Once the old server thread is gone the client quietly closes that connection and
 * reopens the world. Without the hold the server shutdown closes the channel and vanilla shows the
 * "connection lost" screen. The {@code restore.rejoin} marker remains a fallback trigger when the
 * signal packet never arrives.
 */
public final class RestoreReconnect {
    /** Installed by the loader client: reopen a world id (single player). */
    public static volatile Consumer<String> worldOpener;
    /** Installed by the loader client: start a login connection to this server. */
    public static volatile Consumer<ServerData> serverConnector;

    /** Ticks to wait before the first attempt, so the server has time to stop. */
    private static final int START_DELAY = 40;
    /** The new instance is already up when "ready" arrives. */
    private static final int READY_DELAY = 10;
    private static final int RETRY_DELAY = 40;
    /** Ticks allowed for the client to notice the closed connection. */
    private static final int DROP_TIMEOUT = 100;
    /**
     * How long the client keeps dialling. The 1.21.1 NeoForge path answers within seconds, but the
     * other nodes stop the server and rely on the operator (or a supervisor) to start it again, so
     * the window has to cover a full restart.
     */
    private static final int MAX_ATTEMPTS = 90;

    private static boolean waiting;
    private static boolean armed;
    private static boolean remote;
    private static String worldId = "";
    private static ServerData server;
    private static int countdown = -1;
    private static int attempts;
    private static int dropTicks;
    private static String lastWorld;
    /** The listener the client had before the reconnect; a different one means the join is back. */
    private static ClientPacketListener heldListener;

    private RestoreReconnect() {
    }

    /** The server is about to stop but keeps the connection alive; stay in the world. */
    public static void arm(String slot) {
        Minecraft client = Minecraft.getInstance();
        if (client.getSingleplayerServer() != null) {
            BackupRuntime runtime = BackupRuntime.get();
            String id = runtime == null ? "" : runtime.worldKey();
            if (!isValidWorldId(id)) {
                Log.LOGGER.warn("restore hold: no world id, the client cannot reopen the world");
                return;
            }
            worldId = id;
            remote = false;
            // The old server removes the player from its list but keeps this connection open
            // until the world is reloaded; remember which listener is the held one.
            heldListener = client.getConnection();
            Log.LOGGER.info("restore hold for {}: reopening world {}", slot, id);
        } else {
            ServerData data = client.getCurrentServer();
            if (data == null) {
                return;
            }
            server = data;
            remote = true;
            heldListener = null;
            Log.LOGGER.info("restore hold for {}: keeping the connection to {}", slot, data.ip);
        }
        waiting = true;
        armed = false;
        countdown = -1;
        attempts = 0;
        dropTicks = 0;
        announceHold();
    }

    /** The new instance has loaded the snapshot: drop the old connection and log in again. */
    public static void ready(String slot) {
        Minecraft client = Minecraft.getInstance();
        if (client.getSingleplayerServer() != null) {
            arm(slot);
            return;
        }
        ServerData data = client.getCurrentServer();
        if (data == null) {
            disarm();
            return;
        }
        server = data;
        remote = true;
        waiting = false;
        armed = true;
        countdown = READY_DELAY;
        attempts = 0;
        dropTicks = DROP_TIMEOUT;
        clearTitle();
        Log.LOGGER.info("restore ready for {}: reconnecting to {}", slot, data.ip);
        heldListener = client.getConnection();
        Connection connection = client.getConnection() == null
                ? null : client.getConnection().getConnection();
        if (connection != null) {
            connection.disconnect(Component.translatable("minimalbackup.restore.reconnect"));
        }
    }

    public static boolean isArmed() {
        return waiting || armed;
    }

    public static void cancel() {
        disarm();
    }

    /** Called every client tick. */
    public static void tick() {
        Minecraft client = Minecraft.getInstance();
        IntegratedServer integrated = client.getSingleplayerServer();
        if (integrated != null) {
            BackupRuntime runtime = BackupRuntime.get();
            if (runtime != null && isValidWorldId(runtime.worldKey())) {
                lastWorld = runtime.worldKey();
            }
            if (!integrated.isShutdown()) {
                // Still in the held world while the old server saves and stops.
                return;
            }
        }
        if (!waiting && !armed && !armFromMarker()) {
            return;
        }
        if (heldListener != null && client.getConnection() != null
                && client.getConnection() != heldListener) {
            // The login succeeded: the old listener is gone, the new one owns the session.
            disarm();
            return;
        }
        if (waiting) {
            if (remote) {
                if (client.getConnection() != null) {
                    // Held on purpose: no timeout, no reconnect. The ready packet ends the wait.
                    return;
                }
            } else if (integrated != null && !integrated.isShutdown()) {
                // Single player: the old server still owns the world; stay in it.
                return;
            }
            waiting = false;
            armed = true;
            countdown = remote ? START_DELAY : READY_DELAY;
        }
        if (!armed) {
            return;
        }
        if (remote && client.screen instanceof net.minecraft.client.gui.screens.TitleScreen) {
            // The player cancelled the reconnect and is back at the menu. Single player also ends
            // up on the title screen, and that is exactly where the world opener has to run.
            disarm();
            return;
        }
        if (dropTicks > 0) {
            dropTicks--;
            if (client.getConnection() != null) {
                return;
            }
        }
        if (client.getConnection() != null) {
            if (remote || heldListener == null || client.getConnection() != heldListener) {
                // A different listener means the login already succeeded (or the player left).
                disarm();
                return;
            }
            // Single player: the held connection is still open on purpose. The world opener
            // below replaces it; close it quietly first so the vanilla listener cannot show the
            // "connection lost" screen.
        }
        if (countdown < 0) {
            countdown = START_DELAY;
        }
        if (countdown-- > 0) {
            return;
        }
        if (!remote) {
            Consumer<String> opener = worldOpener;
            if (opener == null) {
                // No opener installed yet: keep the held world (and heartbeats) and retry.
                return;
            }
            String id = worldId;
            closeHeldQuietly();
            disarm();
            RestoreHold.release();
            opener.accept(id);
            return;
        }
        if (client.screen instanceof ConnectScreen) {
            countdown = RETRY_DELAY;
            return;
        }
        if (attempts++ >= MAX_ATTEMPTS) {
            Log.LOGGER.warn("restore reconnect: giving up after {} attempts", attempts);
            disarm();
            return;
        }
        if (attempts == 1) {
            showTitle(Component.translatable("minimalbackup.ui.restoring.title"),
                    Component.translatable("minimalbackup.ui.restoring.retry"));
        }
        Consumer<ServerData> connector = serverConnector;
        if (connector != null) {
            connector.accept(server);
        }
        countdown = RETRY_DELAY;
    }

    /** Chat line plus the on-screen title overlay while the server applies the snapshot. */
    private static void announceHold() {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.displayClientMessage(
                    Component.translatable("minimalbackup.ui.restoring.wait"), false);
        }
        showTitle(Component.translatable("minimalbackup.ui.restoring.title"),
                Component.translatable("minimalbackup.ui.restoring.wait"));
    }

    /** Same overlay as the vanilla {@code /title} command. */
    private static void showTitle(Component title, Component subtitle) {
        Minecraft client = Minecraft.getInstance();
        client.gui.setTimes(10, 200, 20);
        client.gui.setTitle(title);
        client.gui.setSubtitle(subtitle);
    }

    private static void clearTitle() {
        Minecraft client = Minecraft.getInstance();
        client.gui.setTitle(null);
        client.gui.setSubtitle(null);
    }

    /** Command-line restores leave a marker; the client reopens the world on its own. */
    private static boolean armFromMarker() {
        BackupRuntime runtime = BackupRuntime.get();
        if (lastWorld == null || runtime == null || worldOpener == null) {
            return false;
        }
        Path marker = runtime.snapshotRoot().resolve(VaultPaths.REJOIN_MARKER_NAME);
        if (!Files.isRegularFile(marker)) {
            return false;
        }
        worldId = lastWorld;
        remote = false;
        armed = true;
        countdown = START_DELAY;
        attempts = 0;
        dropTicks = 0;
        heldListener = Minecraft.getInstance().getConnection();
        return true;
    }

    /**
     * Drops the held single-player connection without letting the vanilla listener turn the close
     * into a "connection lost" screen. The world opener calls {@code clearLevel} right after, so
     * the old channel only has to disappear before the new login starts.
     */
    private static void closeHeldQuietly() {
        ClientPacketListener listener = heldListener;
        if (listener == null) {
            return;
        }
        Connection connection = listener.getConnection();
        if (connection == null || !connection.isConnected()) {
            return;
        }
        try {
            ((ConnectionAccess) connection).minimalbackup$setListener(quietListener());
        } catch (RuntimeException e) {
            Log.LOGGER.warn("restore reconnect: cannot silence the held connection: {}", e.toString());
        }
        connection.disconnect(Component.translatable("minimalbackup.restore.reconnect"));
    }

    /** A listener that swallows the disconnect callback of a connection we close ourselves. */
    private static PacketListener quietListener() {
        return (PacketListener) Proxy.newProxyInstance(RestoreReconnect.class.getClassLoader(),
                new Class<?>[]{PacketListener.class}, (proxy, method, args) -> {
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) {
                        return false;
                    }
                    if (type == int.class) {
                        return 0;
                    }
                    if (type == long.class) {
                        return 0L;
                    }
                    if (method.getName().equals("toString")) {
                        return "minimalbackup quiet client";
                    }
                    if (method.getName().equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (method.getName().equals("equals")) {
                        return proxy == args[0];
                    }
                    return null;
                });
    }

    private static void disarm() {
        clearTitle();
        waiting = false;
        armed = false;
        remote = false;
        worldId = "";
        server = null;
        countdown = -1;
        attempts = 0;
        dropTicks = 0;
        heldListener = null;
    }

    private static boolean isValidWorldId(String id) {
        try {
            VaultPaths.requireWorldKey(id);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
