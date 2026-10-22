package io.github.linkfgfgui.minimalbackup.engine.nbt;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 裸 NBT 字节 → AST，不做任何提升。
 *
 * <p>只接受"根标签名称为空、且恰好消费完全部字节"的输入；任何异常、深嵌套或尾部多余字节
 * 都抛 {@link IOException}，由 {@link NbtCanon} 决定退回原字节。
 */
public final class NbtReader {
    /** 防御恶意/损坏的深嵌套；超出即解析失败而不是 StackOverflowError。 */
    private static final int MAX_DEPTH = 512;

    private static final AtomicLong PARSES = new AtomicLong();

    private NbtReader() {
    }

    /** 已执行的解析次数（测试用：断言每个目标每一侧只解析一次）。 */
    public static long parseCount() {
        return PARSES.get();
    }

    public static void resetParseCount() {
        PARSES.set(0L);
    }

    /** 解析一棵完整的裸 NBT；根名称被丢弃（规范化要求它为空）。 */
    public static NbtTag read(byte[] raw) throws IOException {
        if (raw == null) {
            throw new IOException("null nbt");
        }
        PARSES.incrementAndGet();
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
        int type = in.readUnsignedByte();
        if (type == NbtTag.TAG_END) {
            throw new IOException("root tag is TAG_End");
        }
        readUtf(in);
        NbtTag root = readPayload(in, type, 0);
        if (in.available() != 0) {
            throw new IOException("trailing bytes after root tag: " + in.available());
        }
        return root;
    }

    /** 读取单个节点载荷（列表元素/字面量流复用）。 */
    public static NbtTag readPayload(DataInputStream in, int type, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("nbt nesting deeper than " + MAX_DEPTH);
        }
        switch (type) {
            case NbtTag.TAG_BYTE:
                return new NbtTag.NumericTag(type, in.readByte());
            case NbtTag.TAG_SHORT:
                return new NbtTag.NumericTag(type, in.readShort());
            case NbtTag.TAG_INT:
                return new NbtTag.NumericTag(type, in.readInt());
            case NbtTag.TAG_LONG:
                return new NbtTag.NumericTag(type, in.readLong());
            case NbtTag.TAG_FLOAT:
                return new NbtTag.FloatTag(in.readInt());
            case NbtTag.TAG_DOUBLE:
                return new NbtTag.DoubleTag(in.readLong());
            case NbtTag.TAG_BYTE_ARRAY: {
                int length = in.readInt();
                checkLength(length, in, 1, "byte array");
                byte[] data = new byte[length];
                in.readFully(data);
                return new NbtTag.ByteArrayTag(data);
            }
            case NbtTag.TAG_STRING:
                return new NbtTag.StringTag(readUtf(in));
            case NbtTag.TAG_LIST: {
                int elemType = in.readUnsignedByte();
                int length = in.readInt();
                checkLength(length, in, 1, "list");
                if (length > 0 && elemType == NbtTag.TAG_END) {
                    throw new IOException("list with TAG_End elements");
                }
                List<NbtTag> elements = new ArrayList<>(length);
                for (int i = 0; i < length; i++) {
                    elements.add(readPayload(in, elemType, depth + 1));
                }
                return new NbtTag.ListTag(elemType, elements);
            }
            case NbtTag.TAG_COMPOUND: {
                TreeMap<String, NbtTag> children = new TreeMap<>();
                while (true) {
                    int childType = in.readUnsignedByte();
                    if (childType == NbtTag.TAG_END) {
                        break;
                    }
                    String name = readUtf(in);
                    children.put(name, readPayload(in, childType, depth + 1));
                }
                return new NbtTag.CompoundTag(children);
            }
            case NbtTag.TAG_INT_ARRAY: {
                int length = in.readInt();
                checkLength(length, in, 4, "int array");
                int[] data = new int[length];
                for (int i = 0; i < length; i++) {
                    data[i] = in.readInt();
                }
                return new NbtTag.IntArrayTag(data);
            }
            case NbtTag.TAG_LONG_ARRAY: {
                int length = in.readInt();
                checkLength(length, in, 8, "long array");
                long[] data = new long[length];
                for (int i = 0; i < length; i++) {
                    data[i] = in.readLong();
                }
                return new NbtTag.LongArrayTag(data);
            }
            default:
                throw new IOException("unknown tag id " + type);
        }
    }

    private static void checkLength(int length, DataInputStream in, int width, String what)
            throws IOException {
        if (length < 0) {
            throw new IOException("negative " + what + " length");
        }
        long needed = (long) length * width;
        if (needed > in.available()) {
            throw new IOException("truncated " + what + ": " + length + " entries");
        }
    }

    private static String readUtf(DataInputStream in) throws IOException {
        try {
            return in.readUTF();
        } catch (IOException e) {
            throw new IOException("bad modified-utf8 string", e);
        }
    }
}
