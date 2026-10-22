package io.github.linkfgfgui.minimalbackup.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Single source of truth for configuration keys, defaults and ranges.
 *
 * <p>Platform spec builders only translate this table; they must not restate a default. The same
 * keys and defaults are used by all four nodes, which is what ConfigParityTest checks.
 */
public final class ConfigSchema {
    /** Which file an option belongs to. */
    public enum Side {
        SERVER,
        CLIENT
    }

    /** Value kind, used to pick the right builder method and to clamp. */
    public enum Type {
        BOOL,
        INT,
        LONG
    }

    /**
     * One option.
     *
     * @param key          dotted path inside the config file
     * @param type         value kind
     * @param defaultValue default value
     * @param min          inclusive minimum for numeric kinds
     * @param max          inclusive maximum for numeric kinds
     * @param side         server or client file
     * @param comment      comment written into the generated file
     */
    public record Option(String key, Type type, Object defaultValue, double min, double max,
                         Side side, String comment) {
        public boolean isNumeric() {
            return type == Type.INT || type == Type.LONG;
        }
    }

    private static final List<Option> OPTIONS = build();

    private ConfigSchema() {
    }

    public static List<Option> options() {
        return OPTIONS;
    }

    public static List<Option> options(Side side) {
        List<Option> out = new ArrayList<>();
        for (Option option : OPTIONS) {
            if (option.side() == side) {
                out.add(option);
            }
        }
        return Collections.unmodifiableList(out);
    }

    public static Option find(String key) {
        for (Option option : OPTIONS) {
            if (option.key().equals(key)) {
                return option;
            }
        }
        return null;
    }

    private static List<Option> build() {
        List<Option> list = new ArrayList<>();

        // backup
        list.add(new Option("backup.enabled", Type.BOOL, true, 0, 0, Side.SERVER,
                "Master switch for automatic write capture and automatic snapshots; /backup create "
                        + "is governed by backup.triggerOnManual."));
        list.add(new Option("backup.intervalMinutes", Type.INT, 30, 1, 1440, Side.SERVER,
                "Minutes between automatic interval snapshots (1-1440)."));
        list.add(new Option("backup.triggerOnUnload", Type.BOOL, true, 0, 0, Side.SERVER,
                "Create a snapshot when the world is saved and unloaded."));
        list.add(new Option("backup.triggerOnInterval", Type.BOOL, false, 0, 0, Side.SERVER,
                "Create snapshots on the interval timer."));
        list.add(new Option("backup.triggerOnManual", Type.BOOL, true, 0, 0, Side.SERVER,
                "Allow /backup create."));
        list.add(new Option("backup.maxChangeSetKeys", Type.INT, 200_000, 100, 10_000_000, Side.SERVER,
                "Automatic checkpoint when this many targets are buffered."));
        list.add(new Option("backup.maxChangeSetBytes", Type.LONG, 64L * 1024L * 1024L, 1_048_576,
                4_294_967_296D, Side.SERVER,
                "Automatic checkpoint when this many buffered bytes accumulate."));
        list.add(new Option("backup.fullBaseline", Type.BOOL, false, 0, 0, Side.SERVER,
                "Optional read-only import of every world file as the first block."));
        list.add(new Option("backup.copyJson", Type.BOOL, false, 0, 0, Side.SERVER,
                "Copy world-subdirectory *.json files (advancements, stats) as whole-file zstd copies without diff; off by default."));
        list.add(new Option("backup.captureOnRead", Type.BOOL, true, 0, 0, Side.SERVER,
                "Capture the first read of a target. Disable only for debugging: every first write then pays a COW read."));
        list.add(new Option("backup.retentionKeep", Type.INT, 1000, 1, 1_000_000, Side.SERVER,
                "Keep at least this many snapshots during retention."));
        list.add(new Option("backup.retentionMaxBytes", Type.LONG, 0L, 0, 9_007_199_254_740_991D, Side.SERVER,
                "Delete old snapshots above this vault size; 0 disables the limit."));
        list.add(new Option("backup.zstdLevel", Type.INT, 9, 1, 22, Side.SERVER,
                "zstd compression level (1-22)."));
        list.add(new Option("backup.zstdLong", Type.BOOL, false, 0, 0, Side.SERVER,
                "Enable long-distance matching for deltas."));
        list.add(new Option("backup.keyframeInterval", Type.INT, -1, -1, Integer.MAX_VALUE, Side.SERVER,
                "Store a full block every N versions of a target (N >= 1); -1 never stores one."));
        list.add(new Option("backup.verifyAfterWrite", Type.BOOL, true, 0, 0, Side.SERVER,
                "Re-read and hash every block after writing it."));

        // restore
        list.add(new Option("restore.autoCheckpointBeforeRestore", Type.BOOL, true, 0, 0, Side.SERVER,
                "Checkpoint the current state before arming a restore."));
        list.add(new Option("restore.forceDirty", Type.BOOL, true, 0, 0, Side.SERVER,
                "Force a save after a restore so the result reaches disk."));
        list.add(new Option("restore.restoreMarker", Type.BOOL, true, 0, 0, Side.SERVER,
                "Write restore.marker so a crash resumes the restore on the next start."));

        // ui (client)
        list.add(new Option("ui.showTombstones", Type.BOOL, false, 0, 0, Side.CLIENT,
                "Show deleted snapshots in the time tree."));

        // debug
        list.add(new Option("debug.verboseLogging", Type.BOOL, false, 0, 0, Side.SERVER,
                "Log every capture decision."));
        list.add(new Option("debug.dumpKeyCoverage", Type.BOOL, false, 0, 0, Side.SERVER,
                "Log the set of keys seen by the resolver."));
        list.add(new Option("debug.logEveryCow", Type.BOOL, false, 0, 0, Side.SERVER,
                "WARN on every copy-on-write instead of only the first per target."));

        return Collections.unmodifiableList(list);
    }
}
