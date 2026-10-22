import io.github.linkfgfgui.minimalbackup.engine.Block;
import io.github.linkfgfgui.minimalbackup.engine.Codec;
import io.github.linkfgfgui.minimalbackup.engine.EngineSettings;
import io.github.linkfgfgui.minimalbackup.engine.api.CodecRegistry;
import io.github.linkfgfgui.minimalbackup.engine.nbt.NbtCanon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Minimal horizontal codec benchmark (originally PLAN 1 §4).
 *
 * <p>Reads every {@code *.old.nbt} / {@code *.new.nbt} pair under the fixture directory, measures
 * canonicalisation, FULL compression and DELTA compression of the same bytes through the core
 * {@link Codec} bridge, verifies the round trip, and prints one CSV row per pair plus p50/p95.
 *
 * <p>Rows named {@code *.old.nbt} without a sibling {@code *.new.nbt} are reported as FULL-only.
 * Opaque fixtures ({@code *.mca}, {@code fake-level.dat}) exercise the non-NBT pass-through.
 */
public final class CodecBench {
    private static final int WARMUP = 2;
    private static final int ROUNDS = 3;
    /**
     * Optional addon codec class ({@code -Dbench.codecClass=...}). Mixin is not available in this
     * plain JVM, so an addon column is measured by calling its codec entry points directly. The
     * addon's tree-capture seam is not active on this path: encode time includes parsing both
     * sides, which is the pessimistic number. Byte counts are identical either way.
     */
    private static final String CODEC_CLASS = System.getProperty("bench.codecClass");
    private static final Class<?> CODEC = loadCodec();
    /** Benchmark baseline: keep 3 so historical rows stay comparable; override with -D. */
    private static final EngineSettings SETTINGS = new EngineSettings(
            Integer.getInteger("bench.zstdLevel", 3), false,
            EngineSettings.MAX_ZSTD_LONG_WINDOW_LOG, -1, true);

    private CodecBench() {
    }

    public static void main(String[] args) throws Exception {
        String requested = args.length > 0 ? args[0] : CodecRegistry.DEFAULT_CODEC_ID;
        Path root = Paths.get(args.length > 1 ? args[1] : "bench/fixtures");
        String actual = codecId();
        System.out.println("codec=" + actual + " requested=" + requested
                + " zstdLevel=" + SETTINGS.zstdLevel() + " fixtures=" + root);
        if (!actual.equals(requested)) {
            System.err.println("codec mismatch: the runtime reports '" + actual
                    + "', not '" + requested + "'. Put the addon jar (and its mixin launcher) on"
                    + " BENCH_CP; the core has no other codec.");
            System.exit(4);
        }

        List<Row> rows = new ArrayList<>();
        List<Path> olds = new ArrayList<>();
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".old.nbt"))
                    .sorted()
                    .forEach(olds::add);
        }
        for (Path old : olds) {
            String name = old.getFileName().toString();
            Path next = old.resolveSibling(name.substring(0, name.length() - ".old.nbt".length())
                    + ".new.nbt");
            if (!Files.exists(next)) {
                continue;
            }
            rows.add(measure(root.relativize(old).toString(), Files.readAllBytes(old),
                    Files.readAllBytes(next)));
        }
        for (Path opaque : opaqueFiles(root)) {
            byte[] bytes = Files.readAllBytes(opaque);
            rows.add(measureOpaque(root.relativize(opaque).toString(), bytes));
        }

        System.out.println("fixture,family,raw_old,raw_new,canon_old,canon_new,canon_changed,full,delta,"
                + "delta_pct,canon_us,encode_us,roundtrip");
        int failures = 0;
        for (Row row : rows) {
            if (!row.roundTrip) {
                failures++;
            }
            System.out.println(row.name + "," + row.family + "," + row.rawOld + "," + row.rawNew
                    + "," + row.canonOld + "," + row.canonNew + "," + (row.canonChanged ? "yes" : "no")
                    + "," + row.full + ","
                    + row.delta + "," + String.format(Locale.ROOT, "%.2f", row.deltaPct) + ","
                    + row.canonUs + "," + row.encodeUs + "," + (row.roundTrip ? "ok" : "FAIL"));
        }
        printSummary(rows);
        if (failures > 0) {
            System.err.println(failures + " round-trip failures");
            System.exit(5);
        }
    }

    private static Row measure(String name, byte[] oldRaw, byte[] newRaw) throws IOException {
        boolean chunk = name.contains("__chunk_");
        int type = chunk ? Block.TYPE_CHUNK : Block.TYPE_FILE;
        Row row = new Row(name, chunk ? "chunk" : "file", oldRaw.length, newRaw.length);

        NbtCanon.Result oldResult = repeat(() -> NbtCanon.canonicalize(oldRaw));
        NbtCanon.Result newResult = repeat(() -> NbtCanon.canonicalize(newRaw));
        row.canonOld = oldResult.bytes().length;
        row.canonNew = newResult.bytes().length;
        row.canonChanged = !Arrays.equals(oldResult.bytes(), oldRaw)
                || !Arrays.equals(newResult.bytes(), newRaw);
        row.canonUs = nanosToUs(() -> NbtCanon.canonicalize(newRaw));

        int level = SETTINGS.zstdLevel();
        byte[] full = null;
        long fullNs = Long.MAX_VALUE;
        for (int i = 0; i < WARMUP + ROUNDS; i++) {
            long start = System.nanoTime();
            full = compressFull(newResult.bytes(), level, type);
            long elapsed = System.nanoTime() - start;
            if (i >= WARMUP) {
                fullNs = Math.min(fullNs, elapsed);
            }
        }
        row.full = full.length;
        byte[] restored = decompress(full, null, newResult.bytes().length, type);
        row.roundTrip = Arrays.equals(restored, newResult.bytes());

        if (oldResult.isNbt() && newResult.isNbt()) {
            byte[] aligned = null;
            long deltaNs = Long.MAX_VALUE;
            byte[] deltaBytes = null;
            for (int i = 0; i < WARMUP + ROUNDS; i++) {
                long start = System.nanoTime();
                NbtCanon.Result freshNew = i == 0 ? newResult : NbtCanon.canonicalize(newRaw);
                aligned = NbtCanon.alignCanonical(oldResult.bytes(), freshNew);
                deltaBytes = compressDelta(aligned, oldResult.bytes(), level, type);
                long elapsed = System.nanoTime() - start;
                if (i >= WARMUP) {
                    deltaNs = Math.min(deltaNs, elapsed);
                }
            }
            row.delta = deltaBytes.length;
            row.deltaPct = 100.0 * deltaBytes.length / Math.max(1, row.canonNew);
            byte[] restoredDelta = decompress(deltaBytes, oldResult.bytes(), aligned.length, type);
            row.roundTrip &= Arrays.equals(restoredDelta, aligned);
            row.encodeUs = nanosToUs(deltaNs);
        } else {
            row.delta = row.full;
            row.deltaPct = 100.0 * row.full / Math.max(1, newRaw.length);
            row.encodeUs = nanosToUs(fullNs);
        }
        return row;
    }

    private static Row measureOpaque(String name, byte[] bytes) throws IOException {
        Row row = new Row(name, "opaque", bytes.length, bytes.length);
        NbtCanon.Result result = repeat(() -> NbtCanon.canonicalize(bytes));
        row.canonOld = result.bytes().length;
        row.canonNew = result.bytes().length;
        row.canonUs = nanosToUs(() -> NbtCanon.canonicalize(bytes));
        long fullNs = Long.MAX_VALUE;
        byte[] full = null;
        for (int i = 0; i < WARMUP + ROUNDS; i++) {
            long start = System.nanoTime();
            full = compressFull(bytes, SETTINGS.zstdLevel(), Block.TYPE_FILE);
            long elapsed = System.nanoTime() - start;
            if (i >= WARMUP) {
                fullNs = Math.min(fullNs, elapsed);
            }
        }
        row.full = full.length;
        row.delta = full.length;
        row.deltaPct = 100.0 * full.length / Math.max(1, bytes.length);
        row.encodeUs = nanosToUs(fullNs);
        row.roundTrip = Arrays.equals(decompress(full, null, bytes.length, Block.TYPE_FILE),
                bytes);
        return row;
    }

    private static Class<?> loadCodec() {
        if (CODEC_CLASS == null || CODEC_CLASS.isEmpty()) {
            return null;
        }
        try {
            return Class.forName(CODEC_CLASS);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("bench.codecClass not on the classpath: " + CODEC_CLASS, e);
        }
    }

    private static String codecId() {
        if (CODEC == null) {
            return Codec.id();
        }
        try {
            return (String) CODEC.getMethod("codecId").invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("addon codecId failed", e);
        }
    }

    private static byte[] compressFull(byte[] raw, int level, int type) throws IOException {
        if (CODEC == null) {
            return Codec.compressFull(raw, level, type);
        }
        return (byte[]) invoke("compressFull", new Class<?>[] {byte[].class, int.class, int.class},
                raw, level, type);
    }

    private static byte[] compressDelta(byte[] target, byte[] dict, int level, int type)
            throws IOException {
        if (CODEC == null) {
            return Codec.compressDelta(target, dict, SETTINGS, type);
        }
        return (byte[]) invoke("compressDelta",
                new Class<?>[] {byte[].class, byte[].class, int.class, int.class},
                target, dict, level, type);
    }

    private static byte[] decompress(byte[] patch, byte[] dict, int originalLength, int type)
            throws IOException {
        if (CODEC == null) {
            return Codec.decompress(patch, dict, originalLength, type);
        }
        return (byte[]) invoke("decompress",
                new Class<?>[] {byte[].class, byte[].class, int.class, int.class},
                patch, dict, originalLength, type);
    }

    private static Object invoke(String name, Class<?>[] parameterTypes, Object... args)
            throws IOException {
        try {
            return CODEC.getMethod(name, parameterTypes).invoke(null, args);
        } catch (ReflectiveOperationException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("addon codec " + name + " failed", cause);
        }
    }

    private static List<Path> opaqueFiles(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(path -> {
                String name = path.getFileName().toString();
                if (name.endsWith(".mca") || name.equals("fake-level.dat")) {
                    files.add(path);
                }
            });
        }
        Collections.sort(files);
        return files;
    }

    private static void printSummary(List<Row> rows) {
        List<Row> pairs = new ArrayList<>();
        List<Row> chunks = new ArrayList<>();
        for (Row row : rows) {
            if (row.family.equals("chunk") || row.family.equals("file")) {
                pairs.add(row);
            }
            if (row.family.equals("chunk")) {
                chunks.add(row);
            }
        }
        summary("all", pairs);
        summary("chunk", chunks);
    }

    private static void summary(String label, List<Row> rows) {
        if (rows.isEmpty()) {
            return;
        }
        long[] delta = new long[rows.size()];
        long[] full = new long[rows.size()];
        long[] encode = new long[rows.size()];
        long[] canon = new long[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            delta[i] = rows.get(i).delta;
            full[i] = rows.get(i).full;
            encode[i] = rows.get(i).encodeUs;
            canon[i] = rows.get(i).canonUs;
        }
        System.out.println("summary." + label + " pairs=" + rows.size()
                + " delta_p50=" + percentile(delta, 0.50) + " delta_p95=" + percentile(delta, 0.95)
                + " full_p50=" + percentile(full, 0.50) + " full_p95=" + percentile(full, 0.95)
                + " encode_p50_us=" + percentile(encode, 0.50)
                + " encode_p95_us=" + percentile(encode, 0.95)
                + " canon_p50_us=" + percentile(canon, 0.50)
                + " canon_p95_us=" + percentile(canon, 0.95));
    }

    private static long percentile(long[] values, double fraction) {
        long[] copy = values.clone();
        Arrays.sort(copy);
        int index = (int) Math.ceil(fraction * copy.length) - 1;
        return copy[Math.max(0, Math.min(copy.length - 1, index))];
    }

    private static long nanosToUs(long nanos) {
        return nanos / 1000L;
    }

    private static long nanosToUs(ThrowingSupplier<Object> action) throws IOException {
        long start = System.nanoTime();
        action.get();
        return nanosToUs(System.nanoTime() - start);
    }

    private static <T> T repeat(ThrowingSupplier<T> action) throws IOException {
        T value = null;
        for (int i = 0; i < WARMUP + ROUNDS; i++) {
            value = action.get();
        }
        return value;
    }

    private static final class Row {
        private final String name;
        private final String family;
        private final int rawOld;
        private final int rawNew;
        private int canonOld;
        private int canonNew;
        private boolean canonChanged;
        private int full;
        private int delta;
        private double deltaPct;
        private long canonUs;
        private long encodeUs;
        private boolean roundTrip = true;

        private Row(String name, String family, int rawOld, int rawNew) {
            this.name = name;
            this.family = family;
            this.rawOld = rawOld;
            this.rawNew = rawNew;
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws IOException;
    }
}
