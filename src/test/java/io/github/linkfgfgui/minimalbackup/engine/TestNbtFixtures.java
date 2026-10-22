package io.github.linkfgfgui.minimalbackup.engine;

import io.github.linkfgfgui.minimalbackup.engine.nbt.NbtCanon;
import io.github.linkfgfgui.minimalbackup.engine.nbt.NbtLifter;
import io.github.linkfgfgui.minimalbackup.engine.nbt.NbtReader;
import io.github.linkfgfgui.minimalbackup.engine.nbt.NbtTag;
import io.github.linkfgfgui.minimalbackup.engine.nbt.TreeAligner;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * E14: PLAN 1 §2 的四条不变式跑在 {@code bench/fixtures} 的真实字节上，外加两个坏档风险样本
 * （调色板 {@code long[]} 非零补位、{@code byte[]} 里带尾部垃圾的伪套娃）。
 *
 * <p>PLAN 1 §5.1 要求“在全部真实 fixture 上成立”，合成用例（E13）只证明实现自洽，不证明真
 * 实区块的位宽/键序/浮点位模式都走得通。
 */
final class TestNbtFixtures {
    private TestNbtFixtures() {
    }

    static void run() throws Exception {
        Path fixtures = locateFixtures();
        List<Path> nbtFiles = new ArrayList<>();
        List<Path> opaqueFiles = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(fixtures)) {
            walk.filter(Files::isRegularFile).sorted().forEach(path -> {
                String name = path.getFileName().toString();
                if (name.endsWith(".nbt")) {
                    nbtFiles.add(path);
                } else if (name.endsWith(".mca") || name.equals("fake-level.dat")) {
                    opaqueFiles.add(path);
                }
            });
        }
        TestSupport.check(nbtFiles.size() >= 20, "expected real NBT fixtures, found " + nbtFiles.size());

        for (Path file : nbtFiles) {
            byte[] raw = Files.readAllBytes(file);
            TestSupport.check(NbtCanon.looksLikeNbt(raw), "fixture is bare NBT: " + file);
            byte[] once = NbtCanon.canonicalizeBytes(raw);
            byte[] twice = NbtCanon.canonicalizeBytes(once);
            TestSupport.bytesEq(once, twice, "invariant 1 (idempotent): " + file);

            NbtTag expected = readAndLift(raw);
            NbtTag actual = readAndLift(once);
            TestSupport.eq(expected, actual, "invariant 2 (semantic): " + file);

            TestSupport.bytesEq(once, NbtCanon.alignCanonical(once, once),
                    "invariant 3 (align(c,c)==c): " + file);
        }

        for (Path file : opaqueFiles) {
            byte[] raw = Files.readAllBytes(file);
            TestSupport.bytesEq(raw, NbtCanon.canonicalizeBytes(raw),
                    "invariant 4 (non-NBT immune): " + file);
            TestSupport.bytesEq(raw, NbtCanon.canonicalizeBytes(NbtCanon.canonicalizeBytes(raw)),
                    "invariant 4 idempotent: " + file);
        }

        int pairs = 0;
        TreeAligner.resetAlignCount();
        for (Path old : nbtFiles) {
            String name = old.getFileName().toString();
            if (!name.endsWith(".old.nbt")) {
                continue;
            }
            Path next = old.resolveSibling(name.substring(0, name.length() - ".old.nbt".length())
                    + ".new.nbt");
            if (!Files.exists(next)) {
                continue;
            }
            byte[] oldCanon = NbtCanon.canonicalizeBytes(Files.readAllBytes(old));
            byte[] newCanon = NbtCanon.canonicalizeBytes(Files.readAllBytes(next));
            byte[] aligned = NbtCanon.alignCanonical(oldCanon, newCanon);
            assertSemanticEquals(readAndLift(newCanon), readAndLift(aligned),
                    "alignment is semantics-preserving: " + name);
            TestSupport.bytesEq(aligned, NbtCanon.alignCanonical(oldCanon, newCanon),
                    "alignment is deterministic: " + name);
            TestSupport.bytesEq(aligned, NbtCanon.alignCanonical(aligned, aligned),
                    "aligned bytes are a fixed point: " + name);
            pairs++;
        }
        TestSupport.check(pairs >= 12, "expected real old/new pairs, found " + pairs);
        TestSupport.check(TreeAligner.alignCount() >= pairs,
                "every delta pair goes through TreeAligner.align");

        riskyPalettePadding();
        riskyNestedByteArray();
    }

    /**
     * 语义相等：调色板容器按“每个索引对应的状态”比较，
     * 而不是按调色板顺序和索引数组逐字节比较——对齐的整个目的就是改这两样。
     */
    private static void assertSemanticEquals(NbtTag expected, NbtTag actual, String message) {
        if (expected instanceof NbtTag.PalettedContainerTag left
                && actual instanceof NbtTag.PalettedContainerTag right) {
            TestSupport.eq(left.count(), right.count(), message + " count");
            TestSupport.eq(left.nonSpanning(), right.nonSpanning(), message + " packing");
            TestSupport.eq(left.extraFields(), right.extraFields(), message + " extra fields");
            TestSupport.eq(left.palette().size(), right.palette().size(), message + " palette size");
            for (int i = 0; i < left.count(); i++) {
                NbtTag leftState = left.palette().get(left.indices()[i]);
                NbtTag rightState = right.palette().get(right.indices()[i]);
                TestSupport.eq(leftState, rightState, message + " state at " + i);
            }
            return;
        }
        if (expected instanceof NbtTag.CompoundTag left && actual instanceof NbtTag.CompoundTag right) {
            TestSupport.eq(left.children().keySet(), right.children().keySet(),
                    message + " keys");
            for (String key : left.children().keySet()) {
                assertSemanticEquals(left.get(key), right.get(key), message + " /" + key);
            }
            return;
        }
        if (expected instanceof NbtTag.ListTag left && actual instanceof NbtTag.ListTag right) {
            TestSupport.eq(left.elemTypeId(), right.elemTypeId(), message + " element type");
            TestSupport.eq(left.size(), right.size(), message + " size");
            for (int i = 0; i < left.size(); i++) {
                assertSemanticEquals(left.get(i), right.get(i), message + " [" + i + "]");
            }
            return;
        }
        TestSupport.eq(expected, actual, message);
    }

    private static void riskyPalettePadding() throws Exception {
        // 20 states -> 5 bits per index, 12 per long, so bits 60..63 of each long are unused
        // padding. A non-zero padding nibble must defeat the strict lift (PLAN 1 §6 D2).
        String[] palette = new String[20];
        for (int i = 0; i < palette.length; i++) {
            palette[i] = "minecraft:state_" + i;
        }
        long[] data = new long[(4096 + 11) / 12];
        data[0] = 0xF000000000000000L;
        byte[] raw = singleContainer(palette, data);
        byte[] canon = NbtCanon.canonicalizeBytes(raw);
        NbtTag root = readAndLift(canon);
        NbtTag blockStates = ((NbtTag.CompoundTag) root).get("block_states");
        TestSupport.check(!(blockStates instanceof NbtTag.PalettedContainerTag),
                "non-zero padding bits must not lift");
        long[] stored = ((NbtTag.LongArrayTag) ((NbtTag.CompoundTag) blockStates).get("data"))
                .data();
        TestSupport.check(Arrays.equals(data, stored), "padding bits survive byte-for-byte");
        TestSupport.bytesEq(canon, NbtCanon.canonicalizeBytes(canon),
                "risky container is canonicalisation-stable");
    }

    private static void riskyNestedByteArray() throws Exception {
        // gzip magic + trailing payload: a naive nested-NBT read would decompress or truncate it.
        byte[] blob = new byte[64];
        blob[0] = 0x1F;
        blob[1] = (byte) 0x8B;
        blob[2] = 8;
        for (int i = 3; i < blob.length; i++) {
            blob[i] = (byte) (i * 17);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(NbtTag.TAG_COMPOUND);
            out.writeUTF("");
            out.writeByte(NbtTag.TAG_BYTE_ARRAY);
            out.writeUTF("blob");
            out.writeInt(blob.length);
            out.write(blob);
            out.writeByte(NbtTag.TAG_END);
        }
        byte[] raw = bytes.toByteArray();
        byte[] canon = NbtCanon.canonicalizeBytes(raw);
        TestSupport.bytesEq(raw, canon, "single sorted key: byte-stable");
        NbtTag.CompoundTag root = (NbtTag.CompoundTag) readAndLift(canon);
        TestSupport.bytesEq(blob, ((NbtTag.ByteArrayTag) root.get("blob")).data(),
                "byte[] stays opaque (D1)");
    }

    private static NbtTag readAndLift(byte[] bytes) throws IOException {
        NbtTag root = NbtReader.read(bytes);
        NbtLifter.lift(root);
        return root;
    }

    /** 键序已排序的单容器 compound，便于逐字节断言。 */
    private static byte[] singleContainer(String[] palette, long[] data) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(NbtTag.TAG_COMPOUND);
            out.writeUTF("");
            out.writeByte(NbtTag.TAG_COMPOUND);
            out.writeUTF("block_states");
            out.writeByte(NbtTag.TAG_LIST);
            out.writeUTF("palette");
            out.writeByte(NbtTag.TAG_COMPOUND);
            out.writeInt(palette.length);
            for (String state : palette) {
                out.writeByte(NbtTag.TAG_STRING);
                out.writeUTF("Name");
                out.writeUTF(state);
                out.writeByte(NbtTag.TAG_END);
            }
            out.writeByte(NbtTag.TAG_LONG_ARRAY);
            out.writeUTF("data");
            out.writeInt(data.length);
            for (long value : data) {
                out.writeLong(value);
            }
            out.writeByte(NbtTag.TAG_END);
            out.writeByte(NbtTag.TAG_END);
        }
        return bytes.toByteArray();
    }

    private static Path locateFixtures() {
        Path found = searchUp(codeSourceDir());
        if (found == null) {
            found = searchUp(Path.of("").toAbsolutePath());
        }
        if (found != null) {
            return found;
        }
        throw new AssertionError("bench/fixtures not found from "
                + Path.of("").toAbsolutePath());
    }

    private static Path codeSourceDir() {
        try {
            return Path.of(TestNbtFixtures.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI());
        } catch (Exception e) {
            return null;
        }
    }

    private static Path searchUp(Path start) {
        Path dir = start;
        while (dir != null) {
            Path candidate = dir.resolve("bench").resolve("fixtures");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return null;
    }
}
