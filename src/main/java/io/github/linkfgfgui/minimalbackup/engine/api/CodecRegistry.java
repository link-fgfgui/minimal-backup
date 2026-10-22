package io.github.linkfgfgui.minimalbackup.engine.api;

import io.github.linkfgfgui.minimalbackup.engine.EngineSettings;
import io.github.linkfgfgui.minimalbackup.engine.ZstdDelta;
import java.io.IOException;
import java.util.Objects;

/**
 * Holds the active {@link CodecProvider}.
 *
 * <p>The built-in {@code zstd/1} provider is active until an addon replaces it. Registration must
 * happen during loader initialisation, before a vault is opened; the id is stamped into and
 * validated against {@code index.properties}, so swapping codecs after a vault is loaded is
 * rejected by the timeline.
 *
 * <p>Only one non-default codec can be installed. Registering a provider with a different id while
 * another addon is active throws {@link IllegalStateException}; re-registering the same id is
 * allowed for loader reloads.
 */
public final class CodecRegistry {

    /** Codec id of the built-in implementation. */
    public static final String DEFAULT_CODEC_ID = "zstd/1";

    private static final CodecProvider ZSTD = new ZstdProvider();

    private static volatile CodecProvider active = ZSTD;

    private CodecRegistry() {
    }

    /**
     * Installs {@code provider}. Call this once from the addon's loader entrypoint.
     *
     * @throws IllegalStateException when a different codec is already active
     */
    public static synchronized void register(CodecProvider provider) {
        Objects.requireNonNull(provider, "provider");
        String id = provider.codecId();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("codecId must not be blank");
        }
        CodecProvider current = active;
        if (current != ZSTD && !current.codecId().equals(id)) {
            throw new IllegalStateException(
                    "codec already installed: " + current.codecId() + " (requested " + id + ")");
        }
        active = provider;
    }

    /** The provider the engine currently uses; never {@code null}. */
    public static CodecProvider active() {
        return active;
    }

    /**
     * Restores the built-in provider. Intended for tests and loader reload paths; a running vault
     * must not change codecs.
     */
    public static synchronized void reset() {
        active = ZSTD;
    }

    private static final class ZstdProvider implements CodecProvider {

        @Override
        public String codecId() {
            return DEFAULT_CODEC_ID;
        }

        @Override
        public byte[] compressFull(byte[] raw, int level, int subTargetType) throws IOException {
            return ZstdDelta.compressFull(raw, level);
        }

        // Four-argument compatibility path; the engine always calls the overload below.
        @Override
        public byte[] compressDelta(byte[] target, byte[] dict, int level, int subTargetType)
                throws IOException {
            return ZstdDelta.compressDelta(target, dict, level, false,
                    EngineSettings.MAX_ZSTD_LONG_WINDOW_LOG);
        }

        @Override
        public byte[] compressDelta(byte[] target, byte[] dict, int level, int subTargetType,
                                    boolean longMode, int longWindowLog) throws IOException {
            return ZstdDelta.compressDelta(target, dict, level, longMode, longWindowLog);
        }

        @Override
        public byte[] decompress(byte[] patch, byte[] dict, int originalLength, int subTargetType)
                throws IOException {
            return ZstdDelta.decompress(patch, dict, originalLength);
        }
    }
}
