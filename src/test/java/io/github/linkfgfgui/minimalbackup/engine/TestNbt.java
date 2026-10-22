package io.github.linkfgfgui.minimalbackup.engine;

import io.github.linkfgfgui.minimalbackup.engine.nbt.NbtCanon;
import io.github.linkfgfgui.minimalbackup.engine.nbt.NbtLifter;
import io.github.linkfgfgui.minimalbackup.engine.nbt.NbtReader;
import io.github.linkfgfgui.minimalbackup.engine.nbt.NbtTag;
import io.github.linkfgfgui.minimalbackup.engine.nbt.NbtWriter;
import io.github.linkfgfgui.minimalbackup.engine.nbt.TreeAligner;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * E13: 规范化与对齐（PLAN 1 §2 / §5）。
 *
 * <p>覆盖四条不变式、解析次数（delta = 每侧一次）、对齐器确实被走到、链式 delta 不漂移，
 * 以及含调色板容器的区块 NBT。
 */
final class TestNbt {
    private TestNbt() {
    }

    static void run() throws Exception {
        idempotentAndSemantic();
        specialValuesSurvive();
        nonNbtIsImmune();
        paletteLiftIsByteNoOp();
        alignIsStableAndReordersPalette();
        alignmentWithoutBaseIsNoOp();
        parseCountIsOnePerSide();
        deltaChainDoesNotDrift();
    }

    private static void idempotentAndSemantic() throws Exception {
        byte[] raw = unsortedCompound();
        byte[] once = NbtCanon.canonicalizeBytes(raw);
        byte[] twice = NbtCanon.canonicalizeBytes(once);
        TestSupport.bytesEq(once, twice, "canonicalize is idempotent");
        TestSupport.check(!Arrays.equals(raw, once), "key order actually changed");

        NbtTag expected = NbtReader.read(raw);
        NbtLifter.lift(expected);
        NbtTag actual = NbtReader.read(once);
        NbtLifter.lift(actual);
        TestSupport.eq(expected, actual, "semantic conservation");
    }

    private static void specialValuesSurvive() throws Exception {
        RawNbt raw = new RawNbt().root();
        raw.doubleBits("dnan", 0x7FF8000000000001L);
        raw.floatBits("nan", 0x7FC00001);
        raw.floatBits("negZero", 0x80000000);
        raw.string("surrogate", "a\uD800b");
        raw.byteArray("tail", new byte[] {0x1F, (byte) 0x8B, 0x08, 0x00, 0x01, 0x02});
        raw.end();
        byte[] bytes = raw.done();
        byte[] canon = NbtCanon.canonicalizeBytes(bytes);
        TestSupport.bytesEq(bytes, canon, "special numeric/string values are byte-stable");
        NbtTag root = NbtReader.read(canon);
        TestSupport.check(root instanceof NbtTag.CompoundTag, "root compound");
        NbtTag.CompoundTag compound = (NbtTag.CompoundTag) root;
        TestSupport.eq(0x80000000, ((NbtTag.FloatTag) compound.get("negZero")).bits(),
                "-0.0f raw bits");
        TestSupport.eq(0x7FC00001, ((NbtTag.FloatTag) compound.get("nan")).bits(),
                "non-canonical NaN bits");
        TestSupport.eq(0x7FF8000000000001L, ((NbtTag.DoubleTag) compound.get("dnan")).bits(),
                "non-canonical double NaN bits");
        TestSupport.eq("a\uD800b", ((NbtTag.StringTag) compound.get("surrogate")).value(),
                "isolated surrogate survives writeUTF");
        TestSupport.eq(6, ((NbtTag.ByteArrayTag) compound.get("tail")).data().length,
                "byte array stays opaque, no nested-NBT lifting");
    }

    private static void nonNbtIsImmune() {
        byte[] ascii = TestSupport.text("benchmark level data");
        TestSupport.bytesEq(ascii, NbtCanon.canonicalizeBytes(ascii), "ascii payload");
        byte[] region = TestSupport.bytes(8192, 7);
        region[0] = 0x0A;
        region[1] = 0x00;
        region[2] = 0x01;
        TestSupport.bytesEq(region, NbtCanon.canonicalizeBytes(region),
                "0x0A 0x00 0x01 is not a bare root");
        byte[] truncated = new byte[] {0x0A, 0x00, 0x00, 0x03};
        TestSupport.bytesEq(truncated, NbtCanon.canonicalizeBytes(truncated), "truncated nbt");
        byte[] trailing = new byte[] {0x0A, 0x00, 0x00, 0x00, 0x01};
        TestSupport.bytesEq(trailing, NbtCanon.canonicalizeBytes(trailing), "trailing bytes");
    }

    private static void paletteLiftIsByteNoOp() throws Exception {
        int[] indices = new int[4096];
        indices[100] = 1;
        indices[101] = 1;
        indices[102] = 1;
        byte[] raw = paletteChunk(new String[] {"minecraft:air", "minecraft:stone"}, indices);
        byte[] canon = NbtCanon.canonicalizeBytes(raw);
        TestSupport.bytesEq(raw, canon, "strict palette lift is a byte no-op");
        NbtTag root = NbtReader.read(canon);
        NbtLifter.lift(root);
        NbtTag.PalettedContainerTag container = firstContainer(root);
        TestSupport.eq(4096, container.count(), "block container count");
        TestSupport.eq(4, container.bits(), "min bits for two states");
        TestSupport.check(container.nonSpanning(), "1.16+ packed format detected");
        TestSupport.eq(1, container.indices()[100], "unpacked index");
    }

    private static void alignIsStableAndReordersPalette() throws Exception {
        String[] states = {"minecraft:air", "minecraft:stone", "minecraft:dirt"};
        int[] indices = new int[4096];
        indices[7] = 2;
        indices[8] = 1;
        indices[300] = 2;
        byte[] base = NbtCanon.canonicalizeBytes(paletteChunk(states, indices));
        TestSupport.bytesEq(base, NbtCanon.alignCanonical(base, base), "align(c, c) == c");

        // Same world, different palette order: air stays 0, stone 2, dirt 1.
        String[] reordered = {"minecraft:dirt", "minecraft:air", "minecraft:stone"};
        int[] remapped = new int[4096];
        Arrays.fill(remapped, 1); // index 1 is still "air" in the reordered palette
        remapped[7] = 0;          // dirt
        remapped[8] = 2;          // stone
        remapped[300] = 0;        // dirt
        byte[] shuffled = NbtCanon.canonicalizeBytes(paletteChunk(reordered, remapped));
        TestSupport.check(!Arrays.equals(base, shuffled), "palette order really differs before align");
        byte[] aligned = NbtCanon.alignCanonical(base, shuffled);
        int diff = firstDifference(base, aligned);
        TestSupport.check(diff < 0, "alignment restores the old palette order byte for byte;"
                + " first difference at " + diff + " base=" + (diff < 0 ? 0 : base[diff] & 0xFF)
                + " aligned=" + (diff < 0 ? 0 : aligned[diff] & 0xFF));
    }

    private static void alignmentWithoutBaseIsNoOp() throws Exception {
        int[] indices = new int[4096];
        byte[] raw = paletteChunk(new String[] {"minecraft:air", "minecraft:stone"}, indices);
        byte[] canon = NbtCanon.canonicalizeBytes(raw);
        byte[] aligned = NbtCanon.alignCanonical(TestSupport.text("not nbt"), canon);
        TestSupport.bytesEq(canon, aligned, "non-NBT base leaves the new bytes untouched");
    }

    private static void parseCountIsOnePerSide() throws Exception {
        Path dir = TestSupport.tempDir("nbt-parse-count");
        try {
            Timeline timeline = Timeline.empty();
            Checkpoint checkpoint = new Checkpoint(dir, timeline, TestSupport.SETTINGS);
            byte[] first = paletteChunk(new String[] {"minecraft:air", "minecraft:stone"},
                    indices(1, 2));
            NbtReader.resetParseCount();
            checkpoint.create(new Checkpoint.ChangeSet().put("level.dat", 0L, first), null);
            TestSupport.eq(1, NbtReader.parseCount(), "FULL target parses only the new value");

            byte[] second = paletteChunk(new String[] {"minecraft:air", "minecraft:stone"},
                    indices(2, 3));
            NbtReader.resetParseCount();
            TreeAligner.resetAlignCount();
            checkpoint.create(new Checkpoint.ChangeSet().put("level.dat", 0L, second), null);
            TestSupport.eq(2, NbtReader.parseCount(), "DELTA target parses old and new once each");
            TestSupport.check(TreeAligner.alignCount() > 0, "delta path goes through TreeAligner");
        } finally {
            TestSupport.deleteTree(dir);
        }
    }

    private static void deltaChainDoesNotDrift() throws Exception {
        Path dir = TestSupport.tempDir("nbt-chain");
        try {
            Timeline timeline = Timeline.empty();
            Checkpoint checkpoint = new Checkpoint(dir, timeline, TestSupport.SETTINGS);
            byte[] v1 = paletteChunk(new String[] {"minecraft:air", "minecraft:stone"},
                    indices(1, 0));
            byte[] v2 = paletteChunk(new String[] {"minecraft:air", "minecraft:stone", "minecraft:dirt"},
                    indices(2, 0));
            byte[] v3 = paletteChunk(new String[] {"minecraft:air", "minecraft:stone"},
                    indices(3, 1));
            checkpoint.create(new Checkpoint.ChangeSet().put("level.dat", 0L, v1), null);
            checkpoint.create(new Checkpoint.ChangeSet().put("level.dat", 0L, v2), null);
            checkpoint.create(new Checkpoint.ChangeSet().put("level.dat", 0L, v3), null);

            Path file = VaultPaths.zpackFor(dir, "level.dat");
            BlockTable table = BlockFile.loadTable(file, -1L);
            Block last = table.lastFor(0L);
            byte[] restored = BlockFile.reconstruct(file, table, last);
            TestSupport.eq(3, table.blocks().size(), "three content blocks");
            TestSupport.eq(Block.MODE_DELTA, last.mode(), "last block is a delta");

            byte[] expected = NbtCanon.alignCanonical(
                    NbtCanon.canonicalizeBytes(v2), NbtCanon.canonicalizeBytes(v3));
            TestSupport.bytesEq(expected, restored, "chain reconstructs the stored canonical bytes");
            TestSupport.bytesEq(NbtCanon.canonicalizeBytes(v1),
                    BlockFile.reconstruct(file, table, table.blocks().get(0)),
                    "first block reconstructs");
        } finally {
            TestSupport.deleteTree(dir);
        }
    }

    private static int firstDifference(byte[] left, byte[] right) {
        int limit = Math.min(left.length, right.length);
        for (int i = 0; i < limit; i++) {
            if (left[i] != right[i]) {
                return i;
            }
        }
        return left.length == right.length ? -1 : limit;
    }

    private static int[] indices(int... values) {
        int[] out = new int[4096];
        for (int i = 0; i < values.length; i++) {
            out[20 + i * 3] = values[i];
        }
        return out;
    }

    private static NbtTag.PalettedContainerTag firstContainer(NbtTag root) {
        NbtTag.CompoundTag compound = (NbtTag.CompoundTag) root;
        NbtTag.ListTag sections = (NbtTag.ListTag) compound.get("sections");
        NbtTag.CompoundTag section = (NbtTag.CompoundTag) sections.get(0);
        return (NbtTag.PalettedContainerTag) section.get("block_states");
    }

    /** 手工写出键序刻意打乱的裸 NBT（规范化必须能把它排好）。 */
    private static byte[] unsortedCompound() throws IOException {
        RawNbt raw = new RawNbt().root();
        raw.intTag("zulu", 42);
        raw.open("alpha").string("s", "hello").intTag("n", -7).close();
        raw.listOfInts("mike", new int[] {1, 2, 3});
        raw.intTag("bravo", 7);
        raw.end();
        return raw.done();
    }

    /** 一个含 {@code sections[0].block_states} 的区块 NBT。 */
    private static byte[] paletteChunk(String[] states, int[] indices) throws IOException {
        long[] packed = NbtWriter.packIndices(indices, 4096, 4, true);
        RawNbt raw = new RawNbt().root();
        raw.listOfCompounds("sections", 1);
        raw.openElement();
        raw.byteTag("Y", 0);
        raw.open("block_states");
        raw.longArray("data", packed);
        raw.listOfCompounds("palette", states.length);
        for (String state : states) {
            raw.openElement();
            raw.string("Name", state);
            raw.close();
        }
        raw.close();
        raw.close();
        raw.end();
        return raw.done();
    }

    /** 测试用裸 NBT 写出器；保持调用顺序，用来制造非规范化输入。 */
    private static final class RawNbt {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final DataOutputStream out = new DataOutputStream(bytes);

        RawNbt root() throws IOException {
            out.writeByte(10);
            out.writeUTF("");
            return this;
        }

        RawNbt open(String key) throws IOException {
            out.writeByte(10);
            out.writeUTF(key);
            return this;
        }

        /** 列表元素既没有类型字节也没有名字：列表头已经声明了元素类型。 */
        RawNbt openElement() {
            return this;
        }

        RawNbt close() throws IOException {
            out.writeByte(0);
            return this;
        }

        RawNbt end() throws IOException {
            return close();
        }

        RawNbt intTag(String key, int value) throws IOException {
            out.writeByte(3);
            out.writeUTF(key);
            out.writeInt(value);
            return this;
        }

        RawNbt byteTag(String key, int value) throws IOException {
            out.writeByte(1);
            out.writeUTF(key);
            out.writeByte(value);
            return this;
        }

        RawNbt string(String key, String value) throws IOException {
            out.writeByte(8);
            out.writeUTF(key);
            out.writeUTF(value);
            return this;
        }

        RawNbt floatBits(String key, int bits) throws IOException {
            out.writeByte(5);
            out.writeUTF(key);
            out.writeInt(bits);
            return this;
        }

        RawNbt doubleBits(String key, long bits) throws IOException {
            out.writeByte(6);
            out.writeUTF(key);
            out.writeLong(bits);
            return this;
        }

        RawNbt byteArray(String key, byte[] data) throws IOException {
            out.writeByte(7);
            out.writeUTF(key);
            out.writeInt(data.length);
            out.write(data);
            return this;
        }

        RawNbt longArray(String key, long[] data) throws IOException {
            out.writeByte(12);
            out.writeUTF(key);
            out.writeInt(data.length);
            for (long value : data) {
                out.writeLong(value);
            }
            return this;
        }

        RawNbt listOfInts(String key, int[] values) throws IOException {
            out.writeByte(9);
            out.writeUTF(key);
            out.writeByte(3);
            out.writeInt(values.length);
            for (int value : values) {
                out.writeInt(value);
            }
            return this;
        }

        /** 只写列表头；随后调用方按顺序写出各元素。 */
        RawNbt listOfCompounds(String key, int size) throws IOException {
            out.writeByte(9);
            out.writeUTF(key);
            out.writeByte(10);
            out.writeInt(size);
            return this;
        }

        byte[] done() throws IOException {
            out.flush();
            return bytes.toByteArray();
        }
    }
}
