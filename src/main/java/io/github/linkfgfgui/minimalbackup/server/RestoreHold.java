package io.github.linkfgfgui.minimalbackup.server;

import io.github.linkfgfgui.minimalbackup.engine.Log;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.game.ServerGamePacketListener;

/**
 * Keeps the player connections alive while the server instance is replaced.
 *
 * <p>Before the old server stops, the players are taken off the player list (saved, not
 * disconnected), each connection's packet listener is replaced with a discard handler, and a
 * heartbeat is written to every connection so the client's read timeout never fires. On a
 * dedicated server {@link #ready()} sends the "come back" packet once the replacement instance
 * has loaded the snapshot. Single player uses the same hold across the integrated server stop;
 * the client then closes its end quietly and reopens the world, so {@link #release()} is used
 * instead of {@link #ready()}.
 *
 * <p>The loaders install the version-specific pieces: {@link #connectionGetter} (how to reach the
 * connection from a player), {@link #listenerSetter} (accessor for the listener field),
 * {@link #heartbeatSender} (keep-alive packet class differs per version) and
 * {@link #readySender} (the restore-ready payload).
 */
public final class RestoreHold {
    private static final Log.Logger LOG = Log.LOGGER;
    /** Keep-alive interval; the client's read timeout is 30 seconds. */
    private static final long HEARTBEAT_SECONDS = 8L;
    private static final long CLOSE_DELAY_MILLIS = 1500L;

    public static volatile Function<ServerPlayer, Connection> connectionGetter;
    public static volatile BiConsumer<Connection, PacketListener> listenerSetter;
    public static volatile Consumer<Connection> heartbeatSender;
    public static volatile Consumer<Connection> readySender;
    /** Loaders whose transport is player-based (Forge SimpleChannel) send the ready packet here. */
    public static volatile BiConsumer<ServerPlayer, String> readyPlayerSender;

    private static final List<Connection> held = new CopyOnWriteArrayList<>();
    private static final List<ServerPlayer> heldPlayers = new CopyOnWriteArrayList<>();
    private static volatile ScheduledExecutorService heartbeat;
    private static volatile boolean holding;
    private static volatile String slot = "";

    private RestoreHold() {
    }

    public static boolean holding() {
        return holding;
    }

    public static String slot() {
        return slot;
    }

    /**
     * Server thread: takes the players off the world and keeps their connections open. Returns
     * false when the loader did not install the hooks or no connection could be kept.
     */
    public static boolean hold(MinecraftServer server, List<ServerPlayer> players, String target) {
        Function<ServerPlayer, Connection> getter = connectionGetter;
        BiConsumer<Connection, PacketListener> setter = listenerSetter;
        if (holding || getter == null || setter == null || players.isEmpty()) {
            return false;
        }
        holding = true;
        slot = target;
        held.clear();
        heldPlayers.clear();
        for (ServerPlayer player : players) {
            Connection connection = getter.apply(player);
            if (connection == null || !connection.isConnected()) {
                continue;
            }
            setter.accept(connection, drain());
            held.add(connection);
            heldPlayers.add(player);
            server.getPlayerList().remove(player);
        }
        if (held.isEmpty()) {
            holding = false;
            return false;
        }
        ScheduledExecutorService beats = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "minimalbackup restore heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        beats.scheduleAtFixedRate(RestoreHold::beat, 0L, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        heartbeat = beats;
        LOG.info("restore hold: keeping {} connection(s) alive for {}", held.size(), target);
        return true;
    }

    /** New server thread: the snapshot is loaded, tell the held clients to reconnect. */
    public static void ready() {
        Consumer<Connection> sender = readySender;
        BiConsumer<ServerPlayer, String> playerSender = readyPlayerSender;
        if (!holding) {
            return;
        }
        if (playerSender != null) {
            for (ServerPlayer player : heldPlayers) {
                playerSender.accept(player, slot);
            }
        } else if (sender != null) {
            for (Connection connection : held) {
                if (connection.isConnected()) {
                    sender.accept(connection);
                }
            }
        } else {
            close();
            return;
        }
        LOG.info("restore hold: sent the reconnect signal to {} connection(s)", held.size());
        Thread closer = new Thread(() -> {
            try {
                Thread.sleep(CLOSE_DELAY_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            close();
        }, "minimalbackup restore close");
        closer.setDaemon(true);
        closer.start();
    }

    /** Drops the held connections without telling the clients (failure path). */
    public static void close() {
        List<Connection> connections = List.copyOf(held);
        release();
        for (Connection connection : connections) {
            try {
                connection.disconnect(Component.translatable("minimalbackup.restore.reconnect"));
            } catch (RuntimeException e) {
                LOG.warn("restore hold: cannot close a connection: {}", e.toString());
            }
        }
    }

    /**
     * Stops the heartbeat and forgets the held connections without touching them.
     *
     * <p>Single player uses this once the client has closed its own end for the world reload:
     * sending a disconnect there would race the new login and could surface the vanilla
     * "connection lost" screen.
     */
    public static void release() {
        ScheduledExecutorService beats = heartbeat;
        heartbeat = null;
        if (beats != null) {
            beats.shutdownNow();
        }
        held.clear();
        heldPlayers.clear();
        holding = false;
        slot = "";
    }

    private static void beat() {
        Consumer<Connection> sender = heartbeatSender;
        if (sender == null) {
            return;
        }
        for (Connection connection : held) {
            if (connection.isConnected()) {
                sender.accept(connection);
            }
        }
    }

    /**
     * A no-op listener for the held connections: packets the client keeps sending must not reach a
     * server that is already shutting down. A proxy is used because the play-state listener
     * interface has dozens of methods.
     */
    private static PacketListener drain() {
        return (PacketListener) Proxy.newProxyInstance(RestoreHold.class.getClassLoader(),
                new Class<?>[]{ServerGamePacketListener.class}, (proxy, method, args) -> {
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
                        return "minimalbackup drain";
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
}
