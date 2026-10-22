package io.github.linkfgfgui.minimalbackup.engine;

import io.github.linkfgfgui.minimalbackup.engine.api.CodecRegistry;
import java.io.IOException;

/**
 * Engine-side facade over the active {@link io.github.linkfgfgui.minimalbackup.engine.api.CodecProvider}.
 *
 * <p>Addons must not overwrite this class with a mixin. Implement the four methods of
 * {@code CodecProvider} and register one instance with {@code CodecRegistry} during loader
 * initialisation; the engine then routes every compression and decompression call here.
 *
 * <p>{@code subTargetType} tells a provider whether a payload is a chunk or a whole file without
 * having to parse it. After canonicalisation the non-NBT case (a CLI {@code .mca}, a gzipped
 * {@code .dat}) is a normal path, not an edge case.
 */
public final class Codec {
    private Codec() {
    }

    /** Full block: compress {@code raw} without a dictionary. */
    public static byte[] compressFull(byte[] raw, int level, int subTargetType) throws IOException {
        return CodecRegistry.active().compressFull(raw, level, subTargetType);
    }

    /** Delta block: compress {@code target} with the previous block's bytes as dictionary. */
    public static byte[] compressDelta(byte[] target, byte[] dict, EngineSettings settings,
                                       int subTargetType) throws IOException {
        return CodecRegistry.active().compressDelta(target, dict, settings.zstdLevel(),
                subTargetType, settings.zstdLong(), settings.zstdLongWindowLog());
    }

    /** Codec id of the active provider, stamped into and validated against the timeline index. */
    public static String id() {
        return CodecRegistry.active().codecId();
    }

    /** Decode one block; {@code dict} is {@code null} for FULL blocks. */
    public static byte[] decompress(byte[] patch, byte[] dict, int originalLength,
                                    int subTargetType) throws IOException {
        return CodecRegistry.active().decompress(patch, dict, originalLength, subTargetType);
    }
}
