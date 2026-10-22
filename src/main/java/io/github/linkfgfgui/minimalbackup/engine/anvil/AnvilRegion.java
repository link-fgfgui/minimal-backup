package io.github.linkfgfgui.minimalbackup.engine.anvil;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.Arrays;

/**
 * 纯 JDK 的 Anvil region 头解析与区块记录编码。
 *
 * <p>只处理离线 CLI rollback 需要的部分：8 KiB 头、4 KiB 扇区、内部区块记录和
 * external {@code .mcc} stub。未改动的区块由调用方按原始 {@code 4 + length} 字节复制，
 * 因此不需要解压或识别 LZ4/gzip 等压缩类型。
 */
public final class AnvilRegion {
    public static final int SECTOR_BYTES = 4096;
    public static final int HEADER_BYTES = 8192;
    public static final int CHUNK_COUNT = 1024;
    public static final int MAX_INTERNAL_SECTORS = 255;
    public static final int COMPRESSION_ZLIB = 2;
    public static final int EXTERNAL_FLAG = 0x80;

    private final int[] locations;
    private final int[] timestamps;
    private final boolean truncated;
    private final int invalidCount;

    private AnvilRegion(int[] locations, int[] timestamps, boolean truncated, int invalidCount) {
        this.locations = locations;
        this.timestamps = timestamps;
        this.truncated = truncated;
        this.invalidCount = invalidCount;
    }

    public static AnvilRegion empty() {
        return new AnvilRegion(new int[CHUNK_COUNT], new int[CHUNK_COUNT], false, 0);
    }

    /**
     * 解析 8 KiB 头。短于 8 KiB 的头按 0 补齐并标记 truncated；单个位置项越界时丢弃并计数。
     * {@code fileSize} 必须是实际文件长度，用于验证位置项。
     */
    public static AnvilRegion parse(byte[] header, long fileSize) {
        boolean shortHeader = header.length < HEADER_BYTES;
        byte[] raw = shortHeader ? Arrays.copyOf(header, HEADER_BYTES) : header;
        ByteBuffer buf = ByteBuffer.wrap(raw, 0, HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
        int[] locations = new int[CHUNK_COUNT];
        int[] timestamps = new int[CHUNK_COUNT];
        int invalid = 0;
        for (int i = 0; i < CHUNK_COUNT; i++) {
            int packed = buf.getInt(i * 4);
            timestamps[i] = buf.getInt(SECTOR_BYTES + i * 4);
            if (packed == 0) {
                continue;
            }
            int offset = (packed >>> 8) & 0xFFFFFF;
            int sectors = packed & 0xFF;
            long end = (long) offset * SECTOR_BYTES + (long) sectors * SECTOR_BYTES;
            if (offset < 2 || sectors == 0 || fileSize < HEADER_BYTES || end > fileSize) {
                invalid++;
                continue;
            }
            locations[i] = packed;
        }
        return new AnvilRegion(locations, timestamps, shortHeader, invalid);
    }

    /** 读取最多 8 KiB 头并解析。 */
    public static AnvilRegion parse(FileChannel channel, long fileSize) throws IOException {
        if (fileSize <= 0L) {
            return empty();
        }
        byte[] header = new byte[HEADER_BYTES];
        int read = 0;
        while (read < header.length) {
            int count = channel.read(ByteBuffer.wrap(header, read, header.length - read), read);
            if (count < 0) {
                break;
            }
            read += count;
        }
        return parse(read == header.length ? header : Arrays.copyOf(header, read), fileSize);
    }

    public boolean present(int index) {
        return locations[index] != 0;
    }

    public boolean truncated() {
        return truncated;
    }

    public int invalidCount() {
        return invalidCount;
    }

    public int sectorOffset(int index) {
        return (locations[index] >>> 8) & 0xFFFFFF;
    }

    public int sectorCount(int index) {
        return locations[index] & 0xFF;
    }

    public int timestamp(int index) {
        return timestamps[index];
    }

    public long dataOffset(int index) {
        return (long) sectorOffset(index) * SECTOR_BYTES;
    }

    /**
     * 读取区块的 5 字节前缀。返回 null 表示位置存在但前缀无法读取；调用方应按损坏块丢弃。
     * internal 的 {@code payloadBytes} 是 {@code 4 + length}，external stub 固定 5 字节。
     */
    public Stored stored(FileChannel channel, int index) throws IOException {
        if (!present(index)) {
            return null;
        }
        ByteBuffer prefix = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN);
        long position = dataOffset(index);
        while (prefix.hasRemaining()) {
            int count;
            try {
                count = channel.read(prefix, position + prefix.position());
            } catch (EOFException e) {
                return null;
            }
            if (count < 0) {
                return null;
            }
            if (count == 0) {
                continue;
            }
        }
        prefix.flip();
        int length = prefix.getInt();
        int rawType = prefix.get() & 0xFF;
        int typeId = rawType & 0x7F;
        if ((rawType & EXTERNAL_FLAG) != 0) {
            return new Stored(true, length >= 1, typeId, length, 5);
        }
        long max = (long) sectorCount(index) * SECTOR_BYTES - 4L;
        boolean valid = length >= 1 && length <= max;
        return new Stored(false, valid, typeId, length, valid ? 4 + length : 0);
    }

    public static int localIndex(int chunkX, int chunkZ) {
        return (chunkX & 31) + ((chunkZ & 31) << 5);
    }

    public static long chunkSubTarget(int chunkX, int chunkZ) {
        return ((long) chunkZ << 32) | (chunkX & 0xFFFFFFFFL);
    }

    public static int chunkX(long subTarget) {
        return (int) subTarget;
    }

    public static int chunkZ(long subTarget) {
        return (int) (subTarget >>> 32);
    }

    public static int packLocation(int sectorOffset, int sectorCount) {
        if (sectorOffset < 0 || sectorOffset > 0xFFFFFF) {
            throw new IllegalArgumentException("bad sector offset: " + sectorOffset);
        }
        if (sectorCount < 0 || sectorCount > MAX_INTERNAL_SECTORS) {
            throw new IllegalArgumentException("bad sector count: " + sectorCount);
        }
        if ((sectorOffset == 0) != (sectorCount == 0)) {
            throw new IllegalArgumentException("offset/count must both be zero or both nonzero");
        }
        return (sectorOffset << 8) | sectorCount;
    }

    /** epoch 毫秒转 Anvil 的 epoch 秒，并限制在非负 int 范围。 */
    public static int seconds(long epochMillis) {
        long value = epochMillis / 1000L;
        if (value < 0L) {
            return 0;
        }
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    /** 编码内部区块记录：BE32(length) + type + compressed payload。 */
    public static byte[] encodeChunk(int compressionType, byte[] compressedPayload) {
        if (compressedPayload == null) {
            throw new IllegalArgumentException("null payload");
        }
        checkCompressionType(compressionType);
        int length = compressedPayload.length + 1;
        byte[] out = new byte[4 + length];
        ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN).putInt(length);
        out[4] = (byte) compressionType;
        System.arraycopy(compressedPayload, 0, out, 5, compressedPayload.length);
        return out;
    }

    /** 编码 external chunk stub：BE32(1) + (type | 0x80)。 */
    public static byte[] externalStub(int compressionType) {
        checkCompressionType(compressionType);
        byte[] out = new byte[5];
        ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN).putInt(1);
        out[4] = (byte) (compressionType | EXTERNAL_FLAG);
        return out;
    }

    private static void checkCompressionType(int compressionType) {
        if (compressionType < 0 || compressionType > 0x7F) {
            throw new IllegalArgumentException("bad compression type: " + compressionType);
        }
    }

    /** 区块前缀读取结果。 */
    public record Stored(boolean external, boolean valid, int typeId, int length,
                         int payloadBytes) {
    }
}
