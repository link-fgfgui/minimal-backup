package io.github.linkfgfgui.minimalbackup.engine.nbt;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

/**
 * AST → 裸 NBT 的<b>唯一</b>规范化写出器（PLAN 1 §2）。
 *
 * <p>规则：Compound 键按 {@code String::compareTo} 升序；List 严格保序；名字与字符串用
 * {@code DataOutput.writeUTF}；数值按原 Tag ID 截断；{@link NbtTag.PalettedContainerTag}
 * 按 {@code max(minBits, ceilLog2(palette.size()))} 重新打包回 {@code long[]}。
 *
 * <p>本类的输出就是块的存储字节。除 {@link TreeAligner} 的调色板重排外，任何代码都不得
 * 在写出后再次修改这些字节。
 */
public final class NbtWriter {
    private NbtWriter() {
    }

    /** 写出完整裸 NBT（根名称为空）。 */
    public static byte[] write(NbtTag root) throws IOException {
        if (root == null) {
            throw new IOException("null root");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(root.typeId());
        out.writeUTF("");
        writePayload(out, root);
        out.flush();
        return bytes.toByteArray();
    }

    /** 调色板位宽：{@code max(minBits, ceilLog2(size))}。 */
    public static int bitsFor(int paletteSize, int minBits) {
        if (paletteSize <= 1) {
            return minBits;
        }
        int bits = 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
        return Math.max(minBits, bits);
    }

    /** 按原版打包格式（1.16+ 不跨界 / 1.13~1.15 连续位流）生成 {@code long[]}。 */
    public static long[] packIndices(int[] indices, int count, int bits, boolean nonSpanning) {
        if (bits < 1 || bits > 32) {
            throw new IllegalArgumentException("bad palette bits: " + bits);
        }
        long mask = (1L << bits) - 1L;
        if (nonSpanning) {
            int perLong = 64 / bits;
            long[] packed = new long[(count + perLong - 1) / perLong];
            for (int i = 0; i < count; i++) {
                packed[i / perLong] |= ((long) indices[i] & mask) << ((i % perLong) * bits);
            }
            return packed;
        }
        long[] packed = new long[(count * bits + 63) / 64];
        for (int i = 0; i < count; i++) {
            long value = (long) indices[i] & mask;
            int bit = i * bits;
            int word = bit >>> 6;
            int offset = bit & 63;
            packed[word] |= value << offset;
            if (offset + bits > 64) {
                packed[word + 1] |= value >>> (64 - offset);
            }
        }
        return packed;
    }

    /** {@link #packIndices} 的逆操作。 */
    public static int[] unpackIndices(long[] packed, int count, int bits, boolean nonSpanning) {
        if (bits < 1 || bits > 32) {
            throw new IllegalArgumentException("bad palette bits: " + bits);
        }
        long mask = (1L << bits) - 1L;
        int[] indices = new int[count];
        if (nonSpanning) {
            int perLong = 64 / bits;
            for (int i = 0; i < count; i++) {
                indices[i] = (int) ((packed[i / perLong] >>> ((i % perLong) * bits)) & mask);
            }
            return indices;
        }
        for (int i = 0; i < count; i++) {
            int bit = i * bits;
            int word = bit >>> 6;
            int offset = bit & 63;
            long value = packed[word] >>> offset;
            if (offset + bits > 64) {
                value |= packed[word + 1] << (64 - offset);
            }
            indices[i] = (int) (value & mask);
        }
        return indices;
    }

    /** 一个 {@code long[]} 布局所需的长度（用于提升判据）。 */
    public static int packedLength(int count, int bits, boolean nonSpanning) {
        if (nonSpanning) {
            int perLong = 64 / bits;
            return (count + perLong - 1) / perLong;
        }
        return (count * bits + 63) / 64;
    }

    /** 写出单个节点载荷（不含类型/名字），供补丁的字面量流复用。 */
    public static void writePayload(DataOutputStream out, NbtTag tag) throws IOException {
        if (tag instanceof NbtTag.NumericTag numeric) {
            switch (numeric.typeId()) {
                case NbtTag.TAG_BYTE:
                    out.writeByte((int) numeric.value());
                    return;
                case NbtTag.TAG_SHORT:
                    out.writeShort((int) numeric.value());
                    return;
                case NbtTag.TAG_INT:
                    out.writeInt((int) numeric.value());
                    return;
                default:
                    out.writeLong(numeric.value());
                    return;
            }
        }
        if (tag instanceof NbtTag.FloatTag floatTag) {
            out.writeInt(floatTag.bits());
            return;
        }
        if (tag instanceof NbtTag.DoubleTag doubleTag) {
            out.writeLong(doubleTag.bits());
            return;
        }
        if (tag instanceof NbtTag.ByteArrayTag byteArray) {
            out.writeInt(byteArray.data().length);
            out.write(byteArray.data());
            return;
        }
        if (tag instanceof NbtTag.StringTag stringTag) {
            out.writeUTF(stringTag.value());
            return;
        }
        if (tag instanceof NbtTag.ListTag list) {
            out.writeByte(list.elemTypeId());
            out.writeInt(list.size());
            int expected = list.elemTypeId();
            for (NbtTag element : list.elements()) {
                if (element.typeId() != expected) {
                    throw new IOException("list element type " + element.typeId()
                            + " != declared " + expected);
                }
                writePayload(out, element);
            }
            return;
        }
        if (tag instanceof NbtTag.CompoundTag compound) {
            writeCompound(out, compound.children());
            return;
        }
        if (tag instanceof NbtTag.PalettedContainerTag palette) {
            TreeMap<String, NbtTag> children = new TreeMap<>(palette.extraFields());
            int bits = palette.bits();
            int[] indices = palette.indices();
            int size = palette.palette().size();
            for (int index : indices) {
                if (index < 0 || index >= size) {
                    throw new IOException("palette index " + index + " outside 0.." + (size - 1));
                }
            }
            long[] packed = packIndices(indices, palette.count(), bits, palette.nonSpanning());
            children.put("palette", palette.palette());
            children.put("data", new NbtTag.LongArrayTag(packed));
            writeCompound(out, children);
            return;
        }
        if (tag instanceof NbtTag.IntArrayTag intArray) {
            out.writeInt(intArray.data().length);
            for (int value : intArray.data()) {
                out.writeInt(value);
            }
            return;
        }
        if (tag instanceof NbtTag.LongArrayTag longArray) {
            out.writeInt(longArray.data().length);
            for (long value : longArray.data()) {
                out.writeLong(value);
            }
            return;
        }
        throw new IOException("unknown tag class " + tag.getClass().getName());
    }

    private static void writeCompound(DataOutputStream out, TreeMap<String, NbtTag> children)
            throws IOException {
        for (Map.Entry<String, NbtTag> entry : children.entrySet()) {
            NbtTag value = entry.getValue();
            if (value == null) {
                throw new IOException("null value for key " + entry.getKey());
            }
            out.writeByte(value.typeId());
            out.writeUTF(entry.getKey());
            writePayload(out, value);
        }
        out.writeByte(NbtTag.TAG_END);
    }
}
