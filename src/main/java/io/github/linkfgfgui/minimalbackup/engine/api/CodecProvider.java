package io.github.linkfgfgui.minimalbackup.engine.api;

import java.io.IOException;

/**
 * Stable codec extension surface.
 *
 * <p>An addon implements these four methods and registers one instance through
 * {@link CodecRegistry#register(CodecProvider)} during loader initialisation. The engine keeps the
 * default {@code zstd/1} implementation and delegates every FULL/DELTA compress and every
 * decompress call to the registered provider. The engine calls the six-argument
 * {@code compressDelta} overload that carries long-distance-matching hints; its default
 * implementation forwards to the four-argument method, so an addon can ignore it.
 *
 * <p>The bytes are the canonical payload bytes the engine stores; {@code subTargetType} is
 * {@code Block.TYPE_CHUNK} or {@code Block.TYPE_FILE}. A provider must round-trip them exactly and
 * must not retain the arrays after the call returns.
 *
 * <p>This is intentionally a byte-array API. Engine internals such as {@code NbtTag},
 * {@code TreeAligner} and {@code EngineSettings} are not part of the addon API; an addon that
 * wants a structural diff parses both sides itself.
 *
 * <p>Example:
 * <pre>{@code
 * public final class MyCodec implements CodecProvider {
 *     @Override
 *     public String codecId() {
 *         return "mycodec/1";
 *     }
 *
 *     @Override
 *     public byte[] compressFull(byte[] raw, int level, int subTargetType) throws IOException {
 *         return MyBackend.compressFull(raw, level);
 *     }
 *
 *     @Override
 *     public byte[] compressDelta(byte[] target, byte[] dict, int level, int subTargetType)
 *             throws IOException {
 *         return MyBackend.compressDelta(target, dict, level);
 *     }
 *
 *     @Override
 *     public byte[] decompress(byte[] patch, byte[] dict, int originalLength, int subTargetType)
 *             throws IOException {
 *         return MyBackend.decompress(patch, dict, originalLength);
 *     }
 * }
 * }</pre>
 *
 * <p>Register one instance from the loader entrypoint, before a world or vault is opened:
 * <pre>{@code CodecRegistry.register(new MyCodec());}</pre>
 *
 * <p>The README section "Codec addons" has the same example with imports and the registration
 * notes.
 */
public interface CodecProvider {

    /** Codec id stamped into {@code index.properties}, for example {@code "zstd/1"}. */
    String codecId();

    /** Compress a FULL block without a dictionary. */
    byte[] compressFull(byte[] raw, int level, int subTargetType) throws IOException;

    /** Compress a DELTA block using {@code dict} (the previous stored bytes). */
    byte[] compressDelta(byte[] target, byte[] dict, int level, int subTargetType)
            throws IOException;

    /**
     * Delta overload carrying the engine's long-distance-matching hints. The engine calls this
     * method; the default implementation ignores the hints and forwards to the four-argument
     * method, so existing addons keep working. Providers that support long mode (the built-in
     * {@code zstd/1}) override it.
     */
    default byte[] compressDelta(byte[] target, byte[] dict, int level, int subTargetType,
                                 boolean longMode, int longWindowLog) throws IOException {
        return compressDelta(target, dict, level, subTargetType);
    }

    /** Decode one block; {@code dict} is {@code null} for FULL blocks. */
    byte[] decompress(byte[] patch, byte[] dict, int originalLength, int subTargetType)
            throws IOException;
}
