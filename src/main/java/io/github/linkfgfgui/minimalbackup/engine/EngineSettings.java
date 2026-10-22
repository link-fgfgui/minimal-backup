package io.github.linkfgfgui.minimalbackup.engine;

/**
 * Immutable engine settings.
 *
 * <p>The config layer builds one from validated config values and passes it to the engine
 * operation that needs it. There are no static values and no defaults here: a missing setting is
 * a compile-time error at the call site, not a silent fallback. Range clamping lives only in
 * {@code ConfigSchema}/{@code ConfigValues}; this record does not restate the bounds.
 *
 * @param zstdLevel          zstd compression level (1-22)
 * @param zstdLong           enable long-distance matching for DELTA blocks
 * @param zstdLongWindowLog  window log for long-distance matching (10-27)
 * @param keyframeInterval   store a FULL block every N versions (N >= 1); -1 disables keyframes
 * @param verifyAfterWrite   re-read every appended block and compare it with what was written
 */
public record EngineSettings(int zstdLevel, boolean zstdLong, int zstdLongWindowLog,
                             int keyframeInterval, boolean verifyAfterWrite) {

    /** zstd-jni decompression window limit used by the delta codec. */
    public static final int MAX_ZSTD_LONG_WINDOW_LOG = 27;
}
