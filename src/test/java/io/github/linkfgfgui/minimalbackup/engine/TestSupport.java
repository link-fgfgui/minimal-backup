package io.github.linkfgfgui.minimalbackup.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/** 测试小工具：断言、临时目录、确定性字节。 */
final class TestSupport {
    /** Explicit engine settings for tests; the engine itself has no defaults. */
    static final EngineSettings SETTINGS = settings(3);

    private TestSupport() {
    }

    static EngineSettings settings(int zstdLevel) {
        return new EngineSettings(zstdLevel, false, EngineSettings.MAX_ZSTD_LONG_WINDOW_LOG,
                -1, true);
    }

    static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    static void eq(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
        }
    }

    static void eq(long expected, long actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
        }
    }

    static void bytesEq(byte[] expected, byte[] actual, String message) {
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError(message + ": byte arrays differ (expected "
                    + expected.length + " bytes, actual " + actual.length + " bytes)");
        }
    }

    static byte[] bytes(int length, int seed) {
        byte[] out = new byte[length];
        long state = seed * 6364136223846793005L + 1442695040888963407L;
        for (int i = 0; i < length; i++) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            out[i] = (byte) (state >>> 56);
        }
        return out;
    }

    static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] mutate(byte[] source, int seed) {
        byte[] out = source.clone();
        for (int i = 0; i < 8; i++) {
            int index = Math.floorMod(seed * 37 + i * 101, out.length);
            out[index] ^= (byte) (seed + i + 1);
        }
        return out;
    }

    static Snapshot snap(String slot, int generation, String parent) {
        return new Snapshot.Builder(slot)
                .generation(generation)
                .parentSlot(parent)
                .savedAt(generation * 1000L)
                .source("test")
                .build();
    }

    static BlockFile.NewBlock fullBlock(int number, int generation, long subTarget, byte[] raw)
            throws IOException {
        return fullBlock(number, generation, subTarget, Block.TYPE_FILE, raw);
    }

    static BlockFile.NewBlock fullBlock(int number, int generation, long subTarget,
                                        int subTargetType, byte[] raw) throws IOException {
        byte[] payload = ZstdDelta.compressFull(raw, SETTINGS.zstdLevel());
        Block block = Block.create(number, 0, generation, subTarget, subTargetType,
                Block.MODE_FULL, raw.length, Xxh64.hash(raw), payload.length);
        return new BlockFile.NewBlock(block, payload);
    }

    static BlockFile.NewBlock deltaBlock(int number, int dictionary, int generation, long subTarget,
                                         byte[] raw, byte[] base) throws IOException {
        return deltaBlock(number, dictionary, generation, subTarget, Block.TYPE_FILE, raw, base);
    }

    static BlockFile.NewBlock deltaBlock(int number, int dictionary, int generation, long subTarget,
                                         int subTargetType, byte[] raw, byte[] base)
            throws IOException {
        byte[] payload = ZstdDelta.compressDelta(raw, base, SETTINGS.zstdLevel(),
                SETTINGS.zstdLong(), SETTINGS.zstdLongWindowLog());
        Block block = Block.create(number, dictionary, generation, subTarget, subTargetType,
                Block.MODE_DELTA, raw.length, Xxh64.hash(raw), payload.length);
        return new BlockFile.NewBlock(block, payload);
    }

    static BlockFile.NewBlock absentBlock(int number, int generation, long subTarget) {
        return absentBlock(number, generation, subTarget, Block.TYPE_FILE);
    }

    static BlockFile.NewBlock absentBlock(int number, int generation, long subTarget,
                                          int subTargetType) {
        Block block = Block.create(number, 0, generation, subTarget, subTargetType,
                Block.MODE_ABSENT, 0, 0L, 0);
        return new BlockFile.NewBlock(block, new byte[0]);
    }

    /** 以 0A 00 00 开头的合成区块 NBT，避免测试数据被误判为非 NBT。 */
    static byte[] chunkNbt(int length, int seed) {
        if (length < 3) {
            throw new IllegalArgumentException("chunk NBT must be at least 3 bytes");
        }
        byte[] out = bytes(length, seed);
        out[0] = 0x0A;
        out[1] = 0;
        out[2] = 0;
        return out;
    }

    static Path tempDir(String prefix) throws IOException {
        return Files.createTempDirectory("minimalbackup-" + prefix + "-");
    }

    static void write(Path file, byte[] data) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(file, data);
    }

    static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        List<Path> paths = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(paths::add);
        }
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }

    static void copyTree(Path source, Path target) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(source)) {
            walk.filter(Files::isRegularFile).forEach(files::add);
        }
        for (Path file : files) {
            Path destination = target.resolve(source.relativize(file).toString());
            write(destination, Files.readAllBytes(file));
        }
    }

    static List<String> relativeFiles(Path root) throws IOException {
        List<String> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .map(path -> root.relativize(path).toString().replace('\\', '/'))
                    .sorted()
                    .forEach(files::add);
        }
        return files;
    }

    static void assertTreeEquals(Path expected, Path actual, String message) throws IOException {
        List<String> expectedFiles = relativeFiles(expected);
        List<String> actualFiles = relativeFiles(actual);
        eq(expectedFiles, actualFiles, message + " file list");
        for (String rel : expectedFiles) {
            bytesEq(Files.readAllBytes(expected.resolve(rel)),
                    Files.readAllBytes(actual.resolve(rel)),
                    message + " content of " + rel);
        }
    }

    static void expectIllegalArgument(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message + ": expected IllegalArgumentException");
    }

    static void expectIOException(IoAction action, String message) {
        try {
            action.run();
        } catch (IOException expected) {
            return;
        } catch (Exception other) {
            throw new AssertionError(message + ": expected IOException, got " + other, other);
        }
        throw new AssertionError(message + ": expected IOException");
    }

    @FunctionalInterface
    interface IoAction {
        void run() throws Exception;
    }
}
