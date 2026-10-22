import io.github.linkfgfgui.minimalbackup.hdiff.HdiffCodec;
import java.io.IOException;

/**
 * Bench adapter for addon #1. {@code HdiffCodec} predates the {@code subTargetType} parameter, so
 * this wrapper only adds the unused argument. The addon ignores the core level (it owns its own
 * LZMA2 preset; default 3, same as the other columns).
 */
public final class HdiffBenchCodec {
    private HdiffBenchCodec() {
    }

    public static String codecId() {
        return "hdiff-lzma2/1";
    }

    public static byte[] compressFull(byte[] raw, int level, int subTargetType) throws IOException {
        return HdiffCodec.compressFull(raw);
    }

    public static byte[] compressDelta(byte[] target, byte[] dict, int level, int subTargetType)
            throws IOException {
        return HdiffCodec.compressDelta(target, dict);
    }

    public static byte[] decompress(byte[] patch, byte[] dict, int originalLength,
                                    int subTargetType) throws IOException {
        return HdiffCodec.decompress(patch, dict, originalLength);
    }
}
