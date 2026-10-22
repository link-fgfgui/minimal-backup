package io.github.linkfgfgui.minimalbackup.engine;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 块头值对象与 42 字节大端编解码。
 *
 * <pre>
 * 0   4B  "MBK1"
 * 4   4B  块总长 = 42 + 载荷长度
 * 8   4B  块号（本文件内从 1 开始，严格 +1）
 * 12  4B  字典块号（0 = 无字典）
 * 16  4B  快照代次
 * 20  8B  子目标（区块 = ChunkPos 打包；整文件 = 0）
 * 28  1B  子目标类型（0 = 整文件，1 = 区块）
 * 29  1B  模式（0 = FULL，1 = DELTA，2 = ABSENT）
 * 30  4B  原始未压缩长度
 * 34  8B  XXH64
 * 42      载荷
 * </pre>
 *
 * <p>{@link #offset()} 是块在文件中的绝对偏移，不属于块头，由扫描/缓存加载时填入。
 */
public final class Block {
    public static final int HEADER_SIZE = 42;
    public static final int MAGIC = 0x4D424B31;
    public static final int MODE_FULL = 0;
    public static final int MODE_DELTA = 1;
    public static final int MODE_ABSENT = 2;
    public static final int TYPE_FILE = 0;
    public static final int TYPE_CHUNK = 1;

    private final int number;
    private final int dictionary;
    private final int generation;
    private final long subTarget;
    private final int subTargetType;
    private final int mode;
    private final int originalLength;
    private final long hash;
    private final int totalLength;
    private final long offset;

    private Block(int number, int dictionary, int generation, long subTarget, int subTargetType,
                  int mode, int originalLength, long hash, int totalLength, long offset) {
        this.number = number;
        this.dictionary = dictionary;
        this.generation = generation;
        this.subTarget = subTarget;
        this.subTargetType = subTargetType;
        this.mode = mode;
        this.originalLength = originalLength;
        this.hash = hash;
        this.totalLength = totalLength;
        this.offset = offset;
    }

    /** 构造一个尚未定位的新块。 */
    public static Block create(int number, int dictionary, int generation, long subTarget,
                               int subTargetType, int mode, int originalLength, long hash,
                               int payloadLength) {
        if (number < 1) {
            throw new IllegalArgumentException("block number must be >= 1");
        }
        if (dictionary < 0 || dictionary >= number) {
            throw new IllegalArgumentException("dictionary must be 0 or an earlier block");
        }
        if (generation < 0) {
            throw new IllegalArgumentException("negative generation");
        }
        if (subTargetType != TYPE_FILE && subTargetType != TYPE_CHUNK) {
            throw new IllegalArgumentException("bad sub-target type: " + subTargetType);
        }
        if (mode != MODE_FULL && mode != MODE_DELTA && mode != MODE_ABSENT) {
            throw new IllegalArgumentException("bad mode: " + mode);
        }
        if (originalLength < 0 || payloadLength < 0) {
            throw new IllegalArgumentException("negative length");
        }
        if (mode == MODE_ABSENT) {
            if (payloadLength != 0 || originalLength != 0 || hash != 0L) {
                throw new IllegalArgumentException("ABSENT block must be empty");
            }
        }
        if (mode == MODE_FULL && dictionary != 0) {
            throw new IllegalArgumentException("FULL block may not have a dictionary");
        }
        if (mode == MODE_DELTA && dictionary == 0) {
            throw new IllegalArgumentException("DELTA block requires a dictionary");
        }
        return new Block(number, dictionary, generation, subTarget, subTargetType,
                mode, originalLength, hash, HEADER_SIZE + payloadLength, 0L);
    }

    /** 从 42 字节块头解码；调用方保证 {@code data} 至少有 {@code off + 42} 字节。 */
    public static Block decode(byte[] data, int off) throws IOException {
        if (data == null || off < 0 || off + HEADER_SIZE > data.length) {
            throw new IOException("short block header");
        }
        ByteBuffer buf = ByteBuffer.wrap(data, off, HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
        int magic = buf.getInt();
        if (magic != MAGIC) {
            throw new IOException("bad block magic");
        }
        int total = buf.getInt();
        if (total < HEADER_SIZE) {
            throw new IOException("bad block length: " + total);
        }
        int number = buf.getInt();
        int dictionary = buf.getInt();
        int generation = buf.getInt();
        long subTarget = buf.getLong();
        int type = buf.get() & 0xFF;
        int mode = buf.get() & 0xFF;
        int originalLength = buf.getInt();
        long hash = buf.getLong();
        if (number < 1) {
            throw new IOException("bad block number: " + number);
        }
        if (dictionary < 0 || dictionary >= number) {
            throw new IOException("bad dictionary number: " + dictionary);
        }
        if (generation < 0) {
            throw new IOException("bad generation: " + generation);
        }
        if (type != TYPE_FILE && type != TYPE_CHUNK) {
            throw new IOException("bad sub-target type: " + type);
        }
        if (mode != MODE_FULL && mode != MODE_DELTA && mode != MODE_ABSENT) {
            throw new IOException("bad mode: " + mode);
        }
        if (originalLength < 0) {
            throw new IOException("bad original length: " + originalLength);
        }
        if (mode == MODE_ABSENT && total != HEADER_SIZE) {
            throw new IOException("ABSENT block carries payload");
        }
        return new Block(number, dictionary, generation, subTarget, type, mode,
                originalLength, hash, total, 0L);
    }

    /** 编码 42 字节块头。 */
    public byte[] encodeHeader() {
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(MAGIC);
        buf.putInt(totalLength);
        buf.putInt(number);
        buf.putInt(dictionary);
        buf.putInt(generation);
        buf.putLong(subTarget);
        buf.put((byte) subTargetType);
        buf.put((byte) mode);
        buf.putInt(originalLength);
        buf.putLong(hash);
        return buf.array();
    }

    public Block withOffset(long newOffset) {
        return new Block(number, dictionary, generation, subTarget, subTargetType,
                mode, originalLength, hash, totalLength, newOffset);
    }

    /** 压实重编号：块号与字典块号一起替换，其余字段不变。 */
    public Block renumber(int newNumber, int newDictionary) {
        return new Block(newNumber, newDictionary, generation, subTarget, subTargetType,
                mode, originalLength, hash, totalLength, offset);
    }

    public int payloadLength() {
        return totalLength - HEADER_SIZE;
    }

    public int number() {
        return number;
    }

    public int dictionary() {
        return dictionary;
    }

    public int generation() {
        return generation;
    }

    public long subTarget() {
        return subTarget;
    }

    public int subTargetType() {
        return subTargetType;
    }

    public int mode() {
        return mode;
    }

    public int originalLength() {
        return originalLength;
    }

    public long hash() {
        return hash;
    }

    public int totalLength() {
        return totalLength;
    }

    public long offset() {
        return offset;
    }
}
