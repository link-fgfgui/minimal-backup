package io.github.linkfgfgui.minimalbackup.config;

import io.github.linkfgfgui.minimalbackup.engine.EngineSettings;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * ConfigParityTest: the shared schema is the single source of defaults, so every platform that
 * builds its spec from it is in parity by construction. This test guards the schema itself.
 */
public final class TestConfigSchema {
    private TestConfigSchema() {
    }

    public static void run() {
        Set<String> keys = new HashSet<>();
        for (ConfigSchema.Option option : ConfigSchema.options()) {
            check(keys.add(option.key()), "duplicate key " + option.key());
            check(!option.comment().isEmpty(), "missing comment for " + option.key());
            if (option.isNumeric()) {
                double value = ((Number) option.defaultValue()).doubleValue();
                check(value >= option.min() && value <= option.max(),
                        "default out of range for " + option.key());
                check(option.min() <= option.max(), "bad range for " + option.key());
            }
        }

        eq(16L, ConfigSchema.options(ConfigSchema.Side.SERVER).stream()
                .filter(option -> option.key().startsWith("backup.")).count(), "backup keys");
        eq(3L, ConfigSchema.options(ConfigSchema.Side.SERVER).stream()
                .filter(option -> option.key().startsWith("restore.")).count(), "restore keys");
        eq(1, ConfigSchema.options(ConfigSchema.Side.CLIENT).size(), "client keys");
        check(ConfigSchema.options(ConfigSchema.Side.CLIENT).stream()
                .allMatch(option -> option.key().startsWith("ui.")), "client file only holds ui keys");

        ConfigValues values = ConfigValues.defaults();
        EngineSettings defaults = values.engineSettings();
        eq(9, defaults.zstdLevel(), "default zstd level");
        eq(-1, values.integer("backup.keyframeInterval"), "keyframes disabled by default");
        check(values.bool("backup.captureOnRead"), "captureOnRead default");
        check(!values.bool("backup.copyJson"), "copyJson off by default");
        check(!values.bool("debug.logEveryCow"), "logEveryCow default");

        eq(22, values.set("backup.zstdLevel", 99), "clamp high");
        eq(1, values.set("backup.zstdLevel", -5), "clamp low");
        eq(1, values.set("backup.intervalMinutes", 0), "clamp interval to minimum");
        eq(0L, values.set("backup.retentionMaxBytes", -1L), "long clamp low");
        int keyframeMax = (int) ConfigSchema.find("backup.keyframeInterval").max();
        int keyframeAbove = keyframeMax == Integer.MAX_VALUE ? keyframeMax : keyframeMax + 1;
        eq(keyframeAbove, values.set("backup.keyframeInterval", keyframeAbove),
                "keyframe clamp high");
        eq(-1, values.set("backup.keyframeInterval", -5), "keyframe clamp low");

        values.set("backup.zstdLevel", 7);
        values.set("backup.zstdLong", true);
        EngineSettings changed = values.engineSettings();
        eq(7, changed.zstdLevel(), "engine level");
        check(changed.zstdLong(), "engine long mode");
        values.set("backup.zstdLevel", 3);
        values.set("backup.zstdLong", false);
        EngineSettings restored = values.engineSettings();
        eq(3, restored.zstdLevel(), "engine level restored");
        eq(-1, restored.keyframeInterval(), "engine keyframes disabled");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void eq(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
        }
    }
}
