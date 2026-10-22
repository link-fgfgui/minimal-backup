package io.github.linkfgfgui.minimalbackup.server;

import io.github.linkfgfgui.minimalbackup.config.ConfigHolder;
import io.github.linkfgfgui.minimalbackup.engine.Compactor;
import io.github.linkfgfgui.minimalbackup.engine.Log;
import io.github.linkfgfgui.minimalbackup.engine.Snapshot;
import io.github.linkfgfgui.minimalbackup.ui.BackupRequest;
import io.github.linkfgfgui.minimalbackup.ui.BackupResponse;
import io.github.linkfgfgui.minimalbackup.ui.SnapshotMeta;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The single implementation behind both {@code /backup} and the UI packets. Callers supply
 * permissions and identity; the service performs the operation and returns a structured result
 * whose message is suitable for chat and for the GUI.
 */
public final class BackupService {
    /** Vanilla permission level required for every action that changes the vault or world. */
    public static final int REQUIRED_PERMISSION = 2;
    public static final String PERMISSION_MESSAGE =
            "Minimal Backup: permission level " + REQUIRED_PERMISSION + " required";

    private BackupService() {
    }

    /** Listing and status do not touch the vault, so they stay open to every player. */
    public static boolean isReadOnly(BackupRequest.Action action) {
        return action == BackupRequest.Action.LIST
                || action == BackupRequest.Action.STATUS;
    }

    /**
     * Packet entry authorization. Read-only requests are always allowed; everything else needs
     * {@link #REQUIRED_PERMISSION}. Denials are logged once per request; the caller decides
     * whether to disconnect.
     */
    public static boolean isAllowed(ServerPlayer player, BackupRequest.Action action) {
        if (isReadOnly(action) || (player != null && player.hasPermissions(REQUIRED_PERMISSION))) {
            return true;
        }
        Log.LOGGER.warn("minimalbackup: denied {} for {} ({})", action,
                player == null ? "<unknown>" : player.getName().getString(),
                player == null ? "<unknown>" : player.getUUID());
        return false;
    }

    public static BackupResponse handle(MinecraftServer server, BackupRequest request) {
        return switch (request.action()) {
            case LIST -> list(server);
            case CREATE -> create(server, request.name(), "");
            case RESTORE -> restore(server, request.slot(), request.confirm());
            case DELETE -> delete(server, request.slot());
            case RENAME -> rename(server, request.slot(), request.name());
            case VERIFY -> verify(server, request.slot());
            case GC -> gc(server, request.confirm());
            case BASELINE -> baseline(server, request.confirm());
            case STATUS -> status(server);
            case DISARM -> disarm(server);
        };
    }

    public static BackupResponse list(MinecraftServer server) {
        try {
            BackupRuntime runtime = runtime(server);
            List<SnapshotMeta> snapshots = new ArrayList<>();
            for (Snapshot snapshot : runtime.snapshots()) {
                snapshots.add(SnapshotMeta.from(snapshot));
            }
            return BackupResponse.ok(snapshots.size() + " snapshot(s)", snapshots);
        } catch (IOException | RuntimeException e) {
            return BackupResponse.fail("list", "List failed: " + e.getMessage());
        }
    }

    public static BackupResponse create(MinecraftServer server, String name, String createdBy) {
        if (!ConfigHolder.values().bool("backup.triggerOnManual")) {
            return BackupResponse.fail("manual-disabled",
                    "Manual snapshots are disabled (backup.triggerOnManual=false)");
        }
        try {
            BackupRuntime runtime = runtime(server);
            // Flush queued chunk writes before the checkpoint.
            server.saveEverything(true, true, true);
            Snapshot snapshot = runtime.createSnapshot("manual", name, createdBy);
            return BackupResponse.ok("Created " + snapshot.slot()
                    + " entries=" + snapshot.entryCount()
                    + " logical=" + snapshot.logicalBytes()
                    + " physical=" + snapshot.physicalBytes(), List.of(SnapshotMeta.from(snapshot)));
        } catch (IOException | RuntimeException e) {
            return BackupResponse.fail("create", "Create failed: " + e.getMessage());
        }
    }

    public static BackupResponse restore(MinecraftServer server, String slot, boolean confirm) {
        if (!confirm) {
            return BackupResponse.ok("Run restore " + slot + " confirm to roll back; "
                    + "the current state is checkpointed first.");
        }
        if (!ConfigHolder.values().bool("restore.restoreMarker")) {
            return BackupResponse.fail("marker-disabled",
                    "Restore requires restore.restoreMarker=true: the world is reloaded");
        }
        try {
            // D3: flush queued writes before the pre-restore checkpoint.
            server.saveEverything(true, true, true);
            BackupRuntime runtime = runtime(server);
            Snapshot target = runtime.prepareRestore(slot);
            List<ServerPlayer> players = new ArrayList<>(server.getPlayerList().getPlayers());
            boolean restarting = !server.isSingleplayer() && RestoreHandoff.restart(server);
            for (ServerPlayer player : players) {
                if (!RestoreHandoff.signal(server, player, target.slot())
                        && !server.isSingleplayer() && !restarting) {
                    player.connection.disconnect(Component.translatable(
                            "minimalbackup.restore.reconnect"));
                }
            }
            // Single player keeps the local connection open across the stop the same way the
            // dedicated restart does. The client stays in the world (heartbeats keep the channel
            // alive), reloads the world once the old server thread is gone, then closes the held
            // connection itself. Without the hold the server shutdown closes the channel and the
            // client shows the vanilla "connection lost" screen.
            if (server.isSingleplayer() || restarting) {
                // A published single-player world has LAN guests; only the host has an integrated
                // server to reopen, so only the host's connection is held across the stop.
                List<ServerPlayer> held = players;
                if (server.isSingleplayer()) {
                    held = new ArrayList<>();
                    for (ServerPlayer player : players) {
                        if (server.isSingleplayerOwner(player.getGameProfile())) {
                            held.add(player);
                        }
                    }
                }
                RestoreHold.hold(server, held, target.slot());
            }
            server.halt(false);
            String message = restarting
                    ? "; the server is restarting and the clients will reconnect."
                    : server.isSingleplayer()
                            ? "; the world is reloading."
                            : "; the server is stopping. Re-enter the world to load the snapshot.";
            return BackupResponse.ok("Armed " + target.slot() + message);
        } catch (IOException | RuntimeException e) {
            return BackupResponse.fail("restore", "Restore failed: " + e.getMessage());
        }
    }

    public static BackupResponse delete(MinecraftServer server, String slot) {
        try {
            runtime(server).deleteSnapshot(slot);
            return BackupResponse.ok("Deleted " + slot + " (tombstone kept so later snapshots stay valid)");
        } catch (IOException | RuntimeException e) {
            return BackupResponse.fail("delete", "Delete failed: " + e.getMessage());
        }
    }

    public static BackupResponse rename(MinecraftServer server, String slot, String name) {
        try {
            runtime(server).renameSnapshot(slot, name);
            return BackupResponse.ok("Renamed " + slot + " to " + name);
        } catch (IOException | RuntimeException e) {
            return BackupResponse.fail("rename", "Rename failed: " + e.getMessage());
        }
    }

    public static BackupResponse verify(MinecraftServer server, String slot) {
        try {
            BackupRuntime.VerifyReport report = runtime(server).verify(slot);
            StringBuilder message = new StringBuilder("Verified " + slot + ": targets="
                    + report.targets + " present=" + report.present + " absent=" + report.absent
                    + " passThrough=" + report.passThrough + " failures=" + report.failures);
            for (String error : report.errors) {
                message.append('\n').append("  ").append(error);
            }
            return new BackupResponse(report.failures == 0, report.failures == 0 ? "ok" : "verify",
                    message.toString(), List.of());
        } catch (IOException | RuntimeException e) {
            return BackupResponse.fail("verify", "Verify failed: " + e.getMessage());
        }
    }

    public static BackupResponse gc(MinecraftServer server, boolean confirm) {
        try {
            BackupRuntime runtime = runtime(server);
            if (!confirm) {
                Path root = runtime.snapshotRoot();
                long[] totals = new long[2];
                if (Files.isDirectory(root)) {
                    try (var stream = Files.walk(root)) {
                        stream.filter(Files::isRegularFile).forEach(path -> {
                            try {
                                totals[0]++;
                                totals[1] += Files.size(path);
                            } catch (IOException ignored) {
                                // vanished during the walk
                            }
                        });
                    }
                }
                return BackupResponse.ok("Vault holds " + totals[0] + " files, " + totals[1]
                        + " bytes; run gc confirm to compact");
            }
            Compactor.Report report = runtime.collectGarbage();
            return BackupResponse.ok("GC files=" + report.filesScanned()
                    + " compacted=" + report.filesCompacted()
                    + " deleted=" + report.filesDeleted()
                    + " blocksKept=" + report.blocksKept()
                    + " blocksDropped=" + report.blocksDropped());
        } catch (IOException | RuntimeException e) {
            return BackupResponse.fail("gc", "GC failed: " + e.getMessage());
        }
    }

    public static BackupResponse baseline(MinecraftServer server, boolean confirm) {
        if (!confirm) {
            return BackupResponse.ok("A full baseline reads every region chunk and can stall the "
                    + "server; run baseline confirm in a quiet moment.");
        }
        try {
            FullBaselineImporter.Report report = FullBaselineImporter.run(runtime(server), server);
            return new BackupResponse(report.failures == 0, report.failures == 0 ? "ok" : "baseline",
                    "Full baseline: regions=" + report.regions + " chunksRead=" + report.chunksRead
                            + " chunksPresent=" + report.chunksPresent + " filesRead="
                            + report.filesRead + " filesCaptured=" + report.filesCaptured
                            + " failures=" + report.failures, List.of());
        } catch (IOException | RuntimeException e) {
            return BackupResponse.fail("baseline", "Full baseline failed: " + e.getMessage());
        }
    }

    public static BackupResponse status(MinecraftServer server) {
        try {
            return BackupResponse.ok(runtime(server).statusText());
        } catch (IOException | RuntimeException e) {
            return BackupResponse.fail("status", "Status failed: " + e.getMessage());
        }
    }

    public static BackupResponse disarm(MinecraftServer server) {
        try {
            BackupRuntime runtime = runtime(server);
            String slot = runtime.armedSlot();
            runtime.disarm();
            return BackupResponse.ok("Disarmed " + (slot == null ? "no slot" : slot)
                    + "; coverHits=" + runtime.coverHits());
        } catch (IOException | RuntimeException e) {
            return BackupResponse.fail("disarm", "Disarm failed: " + e.getMessage());
        }
    }

    public static String identity(ServerPlayer player) {
        return player == null ? "" : player.getUUID() + "|" + player.getName().getString();
    }

    private static BackupRuntime runtime(MinecraftServer server) throws IOException {
        return BackupRuntime.init(server.getWorldPath(LevelResource.ROOT));
    }
}
