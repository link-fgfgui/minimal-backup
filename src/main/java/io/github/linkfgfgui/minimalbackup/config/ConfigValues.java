package io.github.linkfgfgui.minimalbackup.config;

import io.github.linkfgfgui.minimalbackup.engine.EngineSettings;
import io.github.linkfgfgui.minimalbackup.engine.Log;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validated configuration values shared by every platform.
 *
 * <p>Platform code reads its spec and calls {@link #set(String, Object)}; out-of-range numerics are
 * clamped with a warning, never an exception. The engine receives values through
 * {@link #engineSettings()}.
 */
public final class ConfigValues {
    // Config reloads may run on a loader thread while the server thread reads values.
    private final Map<String, Object> values = new ConcurrentHashMap<>();

    public ConfigValues() {
        for (ConfigSchema.Option option : ConfigSchema.options()) {
            values.put(option.key(), option.defaultValue());
        }
    }

    /** A fresh holder with all defaults. */
    public static ConfigValues defaults() {
        return new ConfigValues();
    }

    public boolean bool(String key) {
        Object value = values.get(key);
        return value instanceof Boolean flag ? flag : (Boolean) defaultFor(key);
    }

    public int integer(String key) {
        Object value = values.get(key);
        return value instanceof Number number ? number.intValue() : ((Number) defaultFor(key)).intValue();
    }

    public long longValue(String key) {
        Object value = values.get(key);
        return value instanceof Number number ? number.longValue() : ((Number) defaultFor(key)).longValue();
    }

    /**
     * Stores one value, clamping numerics and rejecting unknown keys.
     *
     * @return the value actually stored
     */
    public Object set(String key, Object rawValue) {
        ConfigSchema.Option option = ConfigSchema.find(key);
        if (option == null) {
            throw new IllegalArgumentException("unknown config key: " + key);
        }
        Object stored = coerce(option, rawValue);
        values.put(key, stored);
        return stored;
    }

    /** Builds the immutable settings object the engine takes as a parameter. */
    public EngineSettings engineSettings() {
        return new EngineSettings(integer("backup.zstdLevel"), bool("backup.zstdLong"),
                EngineSettings.MAX_ZSTD_LONG_WINDOW_LOG, integer("backup.keyframeInterval"),
                bool("backup.verifyAfterWrite"));
    }

    private static Object coerce(ConfigSchema.Option option, Object rawValue) {
        if (rawValue == null) {
            return option.defaultValue();
        }
        return switch (option.type()) {
            case BOOL -> rawValue instanceof Boolean ? rawValue : option.defaultValue();
            case INT -> {
                if (rawValue instanceof Number number) {
                    yield (int) clampNumber(option, number);
                }
                yield option.defaultValue();
            }
            case LONG -> {
                if (rawValue instanceof Number number) {
                    yield clampNumber(option, number);
                }
                yield option.defaultValue();
            }
        };
    }

    private static long clampNumber(ConfigSchema.Option option, Number number) {
        long clamped = clamp(number.longValue(), (long) option.min(), (long) option.max());
        if (clamped != number.longValue()) {
            Log.LOGGER.warn("config {}={} out of range; clamped to {}", option.key(),
                    number.longValue(), clamped);
        }
        return clamped;
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private Object defaultFor(String key) {
        ConfigSchema.Option option = ConfigSchema.find(key);
        return option == null ? null : option.defaultValue();
    }
}
