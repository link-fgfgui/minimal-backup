package io.github.linkfgfgui.minimalbackup.config;

import java.util.function.BiConsumer;

/**
 * Process-wide validated configuration.
 *
 * <p>Platform code builds its spec from {@link ConfigSchema}, registers it, and pushes loaded
 * values here through {@link ConfigValues#set(String, Object)}. The server and engine read from
 * this holder; nothing else caches a default.
 */
public final class ConfigHolder {
    private static final ConfigValues VALUES = new ConfigValues();
    private static volatile Runnable refresher;
    private static volatile BiConsumer<String, Object> persister;
    private static volatile boolean loaded;

    private ConfigHolder() {
    }

    /** Platform hook that re-reads its spec into this holder. */
    public static void refresher(Runnable value) {
        refresher = value;
    }

    /** Pulls fresh values from the platform spec, if one is registered. */
    public static void refresh() {
        Runnable current = refresher;
        if (current != null) {
            current.run();
        }
    }

    /** Platform hook that writes one value back to the loader spec and its file. */
    public static void persister(BiConsumer<String, Object> value) {
        persister = value;
    }

    /**
     * Stores one value in the process-wide holder and, when a platform writer is registered,
     * persists it. Used by the client config screen.
     *
     * @return the value actually stored (clamped/validated)
     */
    public static Object persist(String key, Object value) {
        Object stored = VALUES.set(key, value);
        BiConsumer<String, Object> current = persister;
        if (current != null) {
            current.accept(key, stored);
        }
        return stored;
    }

    /** Called by a platform reloader once it has read the loader's spec. */
    public static void markLoaded() {
        loaded = true;
    }

    /** False until a platform reloader has run at least once (the file may not exist yet). */
    public static boolean loaded() {
        return loaded;
    }

    public static ConfigValues values() {
        return VALUES;
    }

}
