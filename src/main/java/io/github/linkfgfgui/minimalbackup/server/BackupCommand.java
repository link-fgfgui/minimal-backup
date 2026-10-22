package io.github.linkfgfgui.minimalbackup.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.linkfgfgui.minimalbackup.config.ConfigHolder;
import io.github.linkfgfgui.minimalbackup.ui.BackupRequest;
import io.github.linkfgfgui.minimalbackup.ui.BackupResponse;
import io.github.linkfgfgui.minimalbackup.ui.SnapshotMeta;
import java.io.IOException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/** {@code /backup} is a thin caller of {@link BackupService}; the UI uses the same service. */
public final class BackupCommand {
    private BackupCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        if (dispatcher.getRoot().getChild("backup") != null) {
            return;
        }
        dispatcher.register(Commands.literal("backup")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("create")
                        .executes(context -> create(context.getSource(), ""))
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(context -> create(context.getSource(),
                                        StringArgumentType.getString(context, "name")))))
                .then(Commands.literal("list")
                        .executes(context -> list(context.getSource())))
                .then(Commands.literal("status")
                        .executes(context -> run(context.getSource(),
                                BackupRequest.of(BackupRequest.Action.STATUS))))
                .then(Commands.literal("reload")
                        .executes(context -> reload(context.getSource())))
                .then(Commands.literal("ui")
                        .executes(context -> ui(context.getSource())))
                .then(Commands.literal("restore")
                        .then(Commands.argument("slot", StringArgumentType.word())
                                .executes(context -> run(context.getSource(), new BackupRequest(
                                        BackupRequest.Action.RESTORE,
                                        StringArgumentType.getString(context, "slot"), "", false)))
                                .then(Commands.literal("confirm")
                                        .executes(context -> run(context.getSource(),
                                                new BackupRequest(BackupRequest.Action.RESTORE,
                                                        StringArgumentType.getString(context, "slot"),
                                                        "", true))))))
                .then(Commands.literal("disarm")
                        .executes(context -> run(context.getSource(),
                                BackupRequest.of(BackupRequest.Action.DISARM))))
                .then(Commands.literal("verify")
                        .then(Commands.argument("slot", StringArgumentType.word())
                                .executes(context -> run(context.getSource(), new BackupRequest(
                                        BackupRequest.Action.VERIFY,
                                        StringArgumentType.getString(context, "slot"), "", false)))))
                .then(Commands.literal("delete")
                        .then(Commands.argument("slot", StringArgumentType.word())
                                .executes(context -> run(context.getSource(), new BackupRequest(
                                        BackupRequest.Action.DELETE,
                                        StringArgumentType.getString(context, "slot"), "", false)))))
                .then(Commands.literal("rename")
                        .then(Commands.argument("slot", StringArgumentType.word())
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(context -> run(context.getSource(),
                                                new BackupRequest(BackupRequest.Action.RENAME,
                                                        StringArgumentType.getString(context, "slot"),
                                                        StringArgumentType.getString(context, "name"),
                                                        false))))))
                .then(Commands.literal("gc")
                        .executes(context -> run(context.getSource(),
                                new BackupRequest(BackupRequest.Action.GC, "", "", false)))
                        .then(Commands.literal("confirm")
                                .executes(context -> run(context.getSource(),
                                        new BackupRequest(BackupRequest.Action.GC, "", "", true)))))
                .then(Commands.literal("baseline")
                        .executes(context -> run(context.getSource(), new BackupRequest(
                                BackupRequest.Action.BASELINE, "", "", false)))
                        .then(Commands.literal("confirm")
                                .executes(context -> run(context.getSource(), new BackupRequest(
                                        BackupRequest.Action.BASELINE, "", "", true))))));
    }

    private static int create(CommandSourceStack source, String name) {
        String createdBy = source.getEntity() instanceof ServerPlayer player
                ? BackupService.identity(player) : "";
        return send(source, BackupService.create(source.getServer(), name, createdBy));
    }

    private static int list(CommandSourceStack source) {
        BackupResponse response = BackupService.list(source.getServer());
        if (!response.ok()) {
            source.sendFailure(Component.literal(response.message()));
            return 0;
        }
        for (String line : response.message().split("\n")) {
            if (!line.isEmpty()) {
                source.sendSuccess(() -> Component.literal(line), false);
            }
        }
        for (SnapshotMeta meta : response.snapshots()) {
            source.sendSuccess(() -> Component.literal(meta.id() + " gen=" + meta.generation()
                    + " parent=" + meta.parent() + " entries=" + meta.entryCount()
                    + " logical=" + meta.logicalBytes() + " physical=" + meta.physicalBytes()
                    + " damaged=" + meta.damaged() + " tombstone=" + meta.tombstone()
                    + (meta.name().isEmpty() ? "" : " name=" + meta.name())
                    + (meta.createdBy().isEmpty() ? "" : " by=" + meta.createdBy())), false);
        }
        return 1;
    }

    private static int run(CommandSourceStack source, BackupRequest request) {
        return send(source, BackupService.handle(source.getServer(), request));
    }

    private static int send(CommandSourceStack source, BackupResponse response) {
        if (response.ok()) {
            for (String line : response.message().split("\n")) {
                if (!line.isEmpty()) {
                    source.sendSuccess(() -> Component.literal(line), false);
                }
            }
            return 1;
        }
        source.sendFailure(Component.literal(response.message()));
        return 0;
    }

    private static int reload(CommandSourceStack source) {
        try {
            BackupRuntime runtime = BackupRuntime.init(
                    source.getServer().getWorldPath(LevelResource.ROOT));
            runtime.refreshConfig();
            source.sendSuccess(() -> Component.literal("Reloaded server config: enabled="
                    + ConfigHolder.values().bool("backup.enabled")
                    + " captureOnRead=" + ConfigHolder.values().bool("backup.captureOnRead")), false);
            return 1;
        } catch (IOException | RuntimeException e) {
            source.sendFailure(Component.literal("Reload failed: " + e.getMessage()));
            return 0;
        }
    }

    private static int ui(CommandSourceStack source) {
        if (!UiBridge.available()) {
            source.sendSuccess(() -> Component.literal(
                    "The snapshot UI is client-side; press the UI key (F9 by default) in game"), false);
            return 0;
        }
        UiBridge.open();
        return 1;
    }
}
