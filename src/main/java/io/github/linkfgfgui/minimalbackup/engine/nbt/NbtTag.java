package io.github.linkfgfgui.minimalbackup.engine.nbt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

/**
 * 内存 AST 节点模型。
 *
 * <p>约定：
 * <ul>
 *   <li>Byte/Short/Int/Long 统一提升为 {@code long}，{@code typeId()} 保留原 Tag ID，写出时按 ID 截断。</li>
 *   <li>Float/Double 分别存 {@code int}/{@code long} 的 raw bits，绝不经过 {@code float}/{@code double}，
 *       以保留 {@code -0.0f} 与模组非标 NaN 位模式。</li>
 *   <li>Compound 用 {@link TreeMap}（{@code String::compareTo} 排序），List 严格保序。</li>
 *   <li>{@link PalettedContainerTag} 是仅在内存中存在的虚拟节点，对外表现为 TAG_Compound。</li>
 * </ul>
 *
 * <p>本类封闭：只有本文件内的嵌套类可以继承。
 */
public abstract class NbtTag {
    public static final int TAG_END = 0;
    public static final int TAG_BYTE = 1;
    public static final int TAG_SHORT = 2;
    public static final int TAG_INT = 3;
    public static final int TAG_LONG = 4;
    public static final int TAG_FLOAT = 5;
    public static final int TAG_DOUBLE = 6;
    public static final int TAG_BYTE_ARRAY = 7;
    public static final int TAG_STRING = 8;
    public static final int TAG_LIST = 9;
    public static final int TAG_COMPOUND = 10;
    public static final int TAG_INT_ARRAY = 11;
    public static final int TAG_LONG_ARRAY = 12;

    private NbtTag() {
    }

    /** 线格式 Tag ID；虚拟节点伪装成它替代的节点。 */
    public abstract int typeId();

    /** 定长调色板容器的元素数：方块 4096，生物群系 64。 */
    public static final class NumericTag extends NbtTag {
        private final int id;
        private final long value;

        public NumericTag(int id, long value) {
            if (id < TAG_BYTE || id > TAG_LONG) {
                throw new IllegalArgumentException("not a numeric tag id: " + id);
            }
            this.id = id;
            this.value = truncate(id, value);
        }

        static long truncate(int id, long value) {
            switch (id) {
                case TAG_BYTE:
                    return (byte) value;
                case TAG_SHORT:
                    return (short) value;
                case TAG_INT:
                    return (int) value;
                default:
                    return value;
            }
        }

        @Override
        public int typeId() {
            return id;
        }

        public long value() {
            return value;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof NumericTag tag && tag.id == id && tag.value == value;
        }

        @Override
        public int hashCode() {
            return 31 * id + Long.hashCode(value);
        }

        @Override
        public String toString() {
            return "Numeric(" + id + "," + value + ")";
        }
    }

    /** TAG_Float：raw bits。 */
    public static final class FloatTag extends NbtTag {
        private final int bits;

        public FloatTag(int bits) {
            this.bits = bits;
        }

        @Override
        public int typeId() {
            return TAG_FLOAT;
        }

        public int bits() {
            return bits;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof FloatTag tag && tag.bits == bits;
        }

        @Override
        public int hashCode() {
            return 31 * TAG_FLOAT + bits;
        }

        @Override
        public String toString() {
            return "Float(0x" + Integer.toHexString(bits) + ")";
        }
    }

    /** TAG_Double：raw bits。 */
    public static final class DoubleTag extends NbtTag {
        private final long bits;

        public DoubleTag(long bits) {
            this.bits = bits;
        }

        @Override
        public int typeId() {
            return TAG_DOUBLE;
        }

        public long bits() {
            return bits;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof DoubleTag tag && tag.bits == bits;
        }

        @Override
        public int hashCode() {
            return 31 * TAG_DOUBLE + Long.hashCode(bits);
        }

        @Override
        public String toString() {
            return "Double(0x" + Long.toHexString(bits) + ")";
        }
    }

    /** TAG_Byte_Array：不透明字节数组（D1 决定不拆套娃）。 */
    public static final class ByteArrayTag extends NbtTag {
        private final byte[] data;

        public ByteArrayTag(byte[] data) {
            if (data == null) {
                throw new IllegalArgumentException("null byte array");
            }
            this.data = data;
        }

        @Override
        public int typeId() {
            return TAG_BYTE_ARRAY;
        }

        /** 只读视图；调用方不得修改。 */
        public byte[] data() {
            return data;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ByteArrayTag tag && Arrays.equals(tag.data, data);
        }

        @Override
        public int hashCode() {
            return 31 * TAG_BYTE_ARRAY + Arrays.hashCode(data);
        }

        @Override
        public String toString() {
            return "ByteArray(" + data.length + ")";
        }
    }

    /** TAG_String。 */
    public static final class StringTag extends NbtTag {
        private final String value;

        public StringTag(String value) {
            if (value == null) {
                throw new IllegalArgumentException("null string");
            }
            this.value = value;
        }

        @Override
        public int typeId() {
            return TAG_STRING;
        }

        public String value() {
            return value;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof StringTag tag && tag.value.equals(value);
        }

        @Override
        public int hashCode() {
            return 31 * TAG_STRING + value.hashCode();
        }

        @Override
        public String toString() {
            return "String(" + value + ")";
        }
    }

    /** TAG_List：严格保序。 */
    public static final class ListTag extends NbtTag {
        private final int elemTypeId;
        private final List<NbtTag> elements;

        public ListTag(int elemTypeId, List<NbtTag> elements) {
            if (elements == null) {
                throw new IllegalArgumentException("null list");
            }
            this.elemTypeId = elemTypeId;
            this.elements = elements;
        }

        public static ListTag empty() {
            return new ListTag(TAG_END, new ArrayList<>());
        }

        @Override
        public int typeId() {
            return TAG_LIST;
        }

        public int elemTypeId() {
            return elemTypeId;
        }

        /** 内部列表；调用方可以原地修改（对齐器依赖这一点）。 */
        public List<NbtTag> elements() {
            return elements;
        }

        public int size() {
            return elements.size();
        }

        public NbtTag get(int index) {
            return elements.get(index);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ListTag tag
                    && tag.elemTypeId == elemTypeId
                    && tag.elements.equals(elements);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * TAG_LIST + elemTypeId) + elements.hashCode();
        }

        @Override
        public String toString() {
            return "List(" + elemTypeId + "," + elements.size() + ")";
        }
    }

    /** TAG_Compound：键按 {@code String::compareTo} 排序。 */
    public static final class CompoundTag extends NbtTag {
        private final TreeMap<String, NbtTag> children;

        public CompoundTag() {
            this.children = new TreeMap<>();
        }

        public CompoundTag(TreeMap<String, NbtTag> children) {
            this.children = children == null ? new TreeMap<>() : children;
        }

        @Override
        public int typeId() {
            return TAG_COMPOUND;
        }

        public TreeMap<String, NbtTag> children() {
            return children;
        }

        public NbtTag get(String key) {
            return children.get(key);
        }

        public NbtTag put(String key, NbtTag value) {
            return children.put(key, value);
        }

        public int size() {
            return children.size();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof CompoundTag tag && tag.children.equals(children);
        }

        @Override
        public int hashCode() {
            return 31 * TAG_COMPOUND + children.hashCode();
        }

        @Override
        public String toString() {
            return "Compound(" + children.size() + ")";
        }
    }

    /** TAG_Int_Array。 */
    public static final class IntArrayTag extends NbtTag {
        private final int[] data;

        public IntArrayTag(int[] data) {
            if (data == null) {
                throw new IllegalArgumentException("null int array");
            }
            this.data = data;
        }

        @Override
        public int typeId() {
            return TAG_INT_ARRAY;
        }

        public int[] data() {
            return data;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof IntArrayTag tag && Arrays.equals(tag.data, data);
        }

        @Override
        public int hashCode() {
            return 31 * TAG_INT_ARRAY + Arrays.hashCode(data);
        }

        @Override
        public String toString() {
            return "IntArray(" + data.length + ")";
        }
    }

    /** TAG_Long_Array。 */
    public static final class LongArrayTag extends NbtTag {
        private final long[] data;

        public LongArrayTag(long[] data) {
            if (data == null) {
                throw new IllegalArgumentException("null long array");
            }
            this.data = data;
        }

        @Override
        public int typeId() {
            return TAG_LONG_ARRAY;
        }

        public long[] data() {
            return data;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof LongArrayTag tag && Arrays.equals(tag.data, data);
        }

        @Override
        public int hashCode() {
            return 31 * TAG_LONG_ARRAY + Arrays.hashCode(data);
        }

        @Override
        public String toString() {
            return "LongArray(" + data.length + ")";
        }
    }

    /**
     * 解包后的调色板容器。
     *
     * <p>{@code indices} 永远按原版 YZX 顺序；{@code count} 是 4096 或 64，{@code minBits} 是
     * 该容器的位宽下限（方块 4，生物群系 1）。写出时按 {@code max(minBits, ceilLog2(palette.size()))}
     * 重新打包。{@code extraFields} 保存 {@code palette}/{@code data} 之外的字段，保证可逆。
     */
    public static final class PalettedContainerTag extends NbtTag {
        private ListTag palette;
        private int[] indices;
        private final boolean nonSpanning;
        private final int count;
        private final int minBits;
        private final TreeMap<String, NbtTag> extraFields;

        public PalettedContainerTag(ListTag palette, int[] indices, boolean nonSpanning, int count,
                                    int minBits, TreeMap<String, NbtTag> extraFields) {
            if (palette == null || indices == null) {
                throw new IllegalArgumentException("null palette container");
            }
            if (indices.length != count) {
                throw new IllegalArgumentException("index array length " + indices.length
                        + " != " + count);
            }
            this.palette = palette;
            this.indices = indices;
            this.nonSpanning = nonSpanning;
            this.count = count;
            this.minBits = minBits;
            this.extraFields = extraFields == null ? new TreeMap<>() : extraFields;
        }

        @Override
        public int typeId() {
            return TAG_COMPOUND;
        }

        public ListTag palette() {
            return palette;
        }

        public void palette(ListTag value) {
            this.palette = value;
        }

        public int[] indices() {
            return indices;
        }

        public void indices(int[] value) {
            if (value.length != count) {
                throw new IllegalArgumentException("index array length mismatch");
            }
            this.indices = value;
        }

        public boolean nonSpanning() {
            return nonSpanning;
        }

        public int count() {
            return count;
        }

        public int minBits() {
            return minBits;
        }

        public int bits() {
            return NbtWriter.bitsFor(palette.size(), minBits);
        }

        public TreeMap<String, NbtTag> extraFields() {
            return extraFields;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof PalettedContainerTag tag
                    && tag.nonSpanning == nonSpanning
                    && tag.count == count
                    && tag.minBits == minBits
                    && tag.palette.equals(palette)
                    && Arrays.equals(tag.indices, indices)
                    && tag.extraFields.equals(extraFields);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * TAG_COMPOUND + palette.hashCode()) + Arrays.hashCode(indices);
        }

        @Override
        public String toString() {
            return "Palette(" + count + "," + palette.size() + " states)";
        }
    }

    /** 按类型常量取名字，仅用于日志。 */
    public static String typeName(int typeId) {
        switch (typeId) {
            case TAG_END:
                return "END";
            case TAG_BYTE:
                return "BYTE";
            case TAG_SHORT:
                return "SHORT";
            case TAG_INT:
                return "INT";
            case TAG_LONG:
                return "LONG";
            case TAG_FLOAT:
                return "FLOAT";
            case TAG_DOUBLE:
                return "DOUBLE";
            case TAG_BYTE_ARRAY:
                return "BYTE_ARRAY";
            case TAG_STRING:
                return "STRING";
            case TAG_LIST:
                return "LIST";
            case TAG_COMPOUND:
                return "COMPOUND";
            case TAG_INT_ARRAY:
                return "INT_ARRAY";
            case TAG_LONG_ARRAY:
                return "LONG_ARRAY";
            default:
                return "UNKNOWN(" + typeId + ")";
        }
    }
}
