package io.github.linkfgfgui.minimalbackup.config;

import java.util.LinkedHashMap;
import java.util.Map;
import net.neoforged.neoforge.common.ModConfigSpec;

/** 1.21.1 Fabric spec builder backed by ForgeConfigAPIPort. */
public final class NeoForgeConfig {
    public static final ModConfigSpec SERVER;
    public static final ModConfigSpec CLIENT;

    private static final Map<String, ModConfigSpec.ConfigValue<?>> VALUES = new LinkedHashMap<>();

    static {
        SERVER = build(ConfigSchema.Side.SERVER);
        CLIENT = build(ConfigSchema.Side.CLIENT);
        ConfigHolder.persister(NeoForgeConfig::write);
    }

    /** Writes one value back to the spec and its file; used by the client config screen. */
    @SuppressWarnings("unchecked")
    private static void write(String key, Object value) {
        ModConfigSpec.ConfigValue<?> configValue = VALUES.get(key);
        ConfigSchema.Option option = ConfigSchema.find(key);
        if (configValue == null || option == null) {
            return;
        }
        (( ModConfigSpec.ConfigValue<Object>) configValue).set(value);
        (option.side() == ConfigSchema.Side.CLIENT ? CLIENT : SERVER).save();
    }

    private NeoForgeConfig() {
    }

    private static ModConfigSpec build(ConfigSchema.Side side) {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        for (ConfigSchema.Option option : ConfigSchema.options(side)) {
            builder.comment(option.comment());
            ModConfigSpec.ConfigValue<?> value = switch (option.type()) {
                case BOOL -> builder.define(option.key(), (Boolean) option.defaultValue());
                case INT -> builder.defineInRange(option.key(),
                        ((Number) option.defaultValue()).intValue(),
                        (int) option.min(), (int) option.max());
                case LONG -> builder.defineInRange(option.key(),
                        ((Number) option.defaultValue()).longValue(),
                        (long) option.min(), (long) option.max());
            };
            VALUES.put(option.key(), value);
        }
        return builder.build();
    }

    public static void reload() {
        ConfigValues values = ConfigHolder.values();
        boolean read = false;
        for (Map.Entry<String, ModConfigSpec.ConfigValue<?>> entry : VALUES.entrySet()) {
            try {
                values.set(entry.getKey(), entry.getValue().get());
                read = true;
            } catch (RuntimeException ignored) {
                // Not loaded yet.
            }
        }
        if (read) {
            ConfigHolder.markLoaded();
        }
    }
}
