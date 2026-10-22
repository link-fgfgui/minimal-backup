package io.github.linkfgfgui.minimalbackup.engine;

import io.github.linkfgfgui.minimalbackup.engine.anvil.AnvilRegion;
import io.github.linkfgfgui.minimalbackup.engine.anvil.AnvilRegionWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DeflaterOutputStream;

/**
 * 离线 rollback：把 vault 里的区块块重建成 Anvil {@code .mca}，并恢复运行时写入的
 * {@code TYPE_FILE} 目标（level.dat / data / playerdata）。
 *
 * <p>区块目标以目标目录中已有的 {@code .mca} 为基底：未被快照覆盖的区块原样复制，
 * 快照覆盖的区块写入，ABSENT 的区块删除。legacy CLI checkpoint 的整文件 {@code .mca}
 * 格式不再支持，遇到时跳过并警告。
 */
public final class Rollback {
    private static final Pattern REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final byte[] EMPTY = new byte[0];

    private final Path snapshotRoot;
    private final Timeline timeline;
    private final Path targetDir;
    private final Resolver resolver;

    public Rollback(Path snapshotRoot, Timeline timeline, Path targetDir) {
        if (snapshotRoot == null || timeline == null || targetDir == null) {
            throw new IllegalArgumentException("null rollback input");
        }
        this.snapshotRoot = snapshotRoot;
        this.timeline = timeline;
        this.targetDir = targetDir;
        this.resolver = new Resolver(snapshotRoot, timeline);
    }

    public Report run(Snapshot target) throws IOException {
        if (target == null) {
            throw new IllegalArgumentException("null target snapshot");
        }
        if (target.damaged()) {
            throw new IOException("snapshot is damaged, strict restore refused: " + target.slot());
        }
        if (target.tombstone()) {
            throw new IOException("snapshot is deleted: " + target.slot());
        }
        if (Files.exists(targetDir) && !Files.isDirectory(targetDir)) {
            throw new IOException("target is not a directory: " + targetDir);
        }
        Files.createDirectories(targetDir);

        Report report = new Report();
        for (Path zpack : VaultPaths.listZpacks(snapshotRoot)) {
            report.keysScanned++;
            String key = VaultPaths.keyForZpack(snapshotRoot, zpack);
            Long cached = timeline.cacheOffset(key);
            BlockTable table = BlockFile.loadTable(zpack, cached == null ? -1L : cached);
            Set<Long> chunkTargets = table.subTargets(Block.TYPE_CHUNK);
            if (!chunkTargets.isEmpty()) {
                restoreRegion(key, table, chunkTargets, target, report);
            } else if (isLegacyWholeFileRegion(key)) {
                report.filesSkipped++;
                report.warn("legacy whole-file region backup is skipped: {}", key);
            } else {
                restoreWholeFile(key, table, target.slot(), report);
            }
        }
        return report;
    }

    private void restoreWholeFile(String key, BlockTable table, String slot, Report report)
            throws IOException {
        Resolver.Result result = resolver.resolve(key, table, 0L, Block.TYPE_FILE, slot);
        Path out = targetDir.resolve(key);
        switch (result.kind()) {
            case PRESENT -> {
                Path parent = out.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                AtomicFiles.write(out, result.data());
                report.filesRestored++;
            }
            case ABSENT -> {
                if (Files.deleteIfExists(out)) {
                    report.filesDeleted++;
                }
            }
            case PASSTHROUGH -> report.filesSkipped++;
        }
    }

    private void restoreRegion(String key, BlockTable table, Set<Long> chunkTargets,
                               Snapshot target, Report report) throws IOException {
        int[] region = parseRegionName(key);
        int regionX = region[0];
        int regionZ = region[1];
        for (long subTarget : chunkTargets) {
            int chunkX = AnvilRegion.chunkX(subTarget);
            int chunkZ = AnvilRegion.chunkZ(subTarget);
            if ((chunkX >> 5) != regionX || (chunkZ >> 5) != regionZ) {
                throw new IOException("chunk " + chunkX + "," + chunkZ
                        + " does not belong to " + key);
            }
        }
        if (table.hasBlockOfType(Block.TYPE_FILE)) {
            report.warn("ignoring legacy whole-file block(s) in {}", key);
        }

        Path out = targetDir.resolve(key);
        Path parent = out.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.exists(out) && !Files.isRegularFile(out)) {
            throw new IOException("region target is not a regular file: " + out);
        }

        int timestamp = AnvilRegion.seconds(target.savedAt());
        Path temp = AtomicFiles.tempFor(out);
        List<ExternalWrite> externalWrites = new ArrayList<>();
        Set<Path> externalTargets = new HashSet<>();
        Set<Path> staleMcc = new LinkedHashSet<>();
        AnvilRegion header = AnvilRegion.empty();
        FileChannel base = null;
        try {
            if (Files.isRegularFile(out)) {
                long fileSize = Files.size(out);
                base = FileChannel.open(out, StandardOpenOption.READ);
                header = AnvilRegion.parse(base, fileSize);
                if (header.truncated()) {
                    report.warn("truncated region header, base chunks dropped: {}", out);
                }
                if (header.invalidCount() > 0) {
                    report.chunksDropped += header.invalidCount();
                    report.warn("dropped {} invalid base location(s) in {}",
                            header.invalidCount(), out);
                }
            } else {
                report.warn("no base region file; only captured chunks are restored: {}", out);
            }

            try (AnvilRegionWriter writer = new AnvilRegionWriter(temp)) {
                for (int index = 0; index < AnvilRegion.CHUNK_COUNT; index++) {
                    int chunkX = (regionX << 5) + (index & 31);
                    int chunkZ = (regionZ << 5) + (index >> 5);
                    long subTarget = AnvilRegion.chunkSubTarget(chunkX, chunkZ);
                    Path mcc = mccPath(out, chunkX, chunkZ);
                    AnvilRegion.Stored stored = null;
                    if (base != null && header.present(index)) {
                        stored = header.stored(base, index);
                        if (stored == null) {
                            report.chunksDropped++;
                            report.warn("dropped unreadable base chunk {}#{}", key, subTarget);
                        } else if (!stored.valid()) {
                            report.chunksDropped++;
                            report.warn("dropped invalid base chunk {}#{}", key, subTarget);
                            stored = null;
                        }
                    }

                    if (chunkTargets.contains(subTarget)) {
                        Resolver.Result result = resolver.resolve(key, table, subTarget,
                                Block.TYPE_CHUNK, target.slot());
                        switch (result.kind()) {
                            case PRESENT -> {
                                byte[] nbt = result.data();
                                requireChunkNbt(nbt, key, subTarget);
                                byte[] compressed = zlib(nbt);
                                byte[] record = AnvilRegion.encodeChunk(
                                        AnvilRegion.COMPRESSION_ZLIB, compressed);
                                int sectors = (record.length + AnvilRegion.SECTOR_BYTES - 1)
                                        / AnvilRegion.SECTOR_BYTES;
                                if (sectors > AnvilRegion.MAX_INTERNAL_SECTORS) {
                                    Path mccTemp = AtomicFiles.tempFor(mcc);
                                    externalWrites.add(new ExternalWrite(mccTemp, mcc));
                                    Files.write(mccTemp, compressed);
                                    externalTargets.add(mcc);
                                    writer.writeChunk(index,
                                            AnvilRegion.externalStub(AnvilRegion.COMPRESSION_ZLIB),
                                            timestamp);
                                    report.externalChunks++;
                                } else {
                                    writer.writeChunk(index, record, timestamp);
                                    if (stored != null && stored.external()) {
                                        staleMcc.add(mcc);
                                    }
                                }
                                report.chunksWritten++;
                            }
                            case ABSENT -> {
                                writer.clear(index, timestamp);
                                if (stored != null && stored.external()) {
                                    staleMcc.add(mcc);
                                }
                                report.chunksRemoved++;
                            }
                            case PASSTHROUGH -> preserveBase(index, stored, writer, header, base,
                                    mcc, report);
                        }
                    } else {
                        preserveBase(index, stored, writer, header, base, mcc, report);
                    }
                }
                writer.finish();
            }

            if (base != null) {
                copyPermissions(out, temp, report);
            }
            for (ExternalWrite external : externalWrites) {
                if (Files.isRegularFile(external.target)) {
                    copyPermissions(external.target, external.temp, report);
                }
            }
            for (ExternalWrite external : externalWrites) {
                AtomicFiles.commit(external.temp, external.target);
            }
            AtomicFiles.commit(temp, out);
            for (Path path : staleMcc) {
                if (externalTargets.contains(path)) {
                    continue;
                }
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    report.warn("cannot delete stale external chunk {}: {}", path, e.toString());
                }
            }
            report.filesRestored++;
            report.regionsRebuilt++;
        } catch (IOException | RuntimeException e) {
            deleteQuietly(temp);
            for (ExternalWrite external : externalWrites) {
                deleteQuietly(external.temp);
            }
            throw e;
        } finally {
            if (base != null) {
                try {
                    base.close();
                } catch (IOException ignored) {
                    // read-only handle; close failure does not change the result
                }
            }
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort cleanup; the caller is already failing
        }
    }

    private static void preserveBase(int index, AnvilRegion.Stored stored,
                                     AnvilRegionWriter writer, AnvilRegion header,
                                     FileChannel base, Path mcc, Report report)
            throws IOException {
        if (stored == null) {
            return;
        }
        if (stored.external()) {
            writer.writeChunk(index, AnvilRegion.externalStub(stored.typeId()),
                    header.timestamp(index));
            if (!Files.isRegularFile(mcc)) {
                report.warn("external chunk file is missing, keeping dangling stub: {}", mcc);
            }
        } else {
            byte[] raw = readExactly(base, header.dataOffset(index), stored.payloadBytes());
            writer.writeChunk(index, raw, header.timestamp(index));
        }
        report.chunksPreserved++;
    }

    private static byte[] readExactly(FileChannel channel, long offset, int length)
            throws IOException {
        if (length == 0) {
            return EMPTY;
        }
        byte[] data = new byte[length];
        ByteBuffer buffer = ByteBuffer.wrap(data);
        long position = offset;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position);
            if (read < 0) {
                throw new IOException("unexpected end of region file");
            }
            if (read == 0) {
                continue;
            }
            position += read;
        }
        return data;
    }

    private static byte[] zlib(byte[] raw) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length / 2));
        try (DeflaterOutputStream deflate = new DeflaterOutputStream(out)) {
            deflate.write(raw);
        }
        return out.toByteArray();
    }

    private static void requireChunkNbt(byte[] data, String key, long subTarget)
            throws IOException {
        if (data == null || data.length < 3
                || data[0] != 0x0A || data[1] != 0 || data[2] != 0) {
            throw new IOException("chunk target is not unnamed NBT: " + key + "#" + subTarget);
        }
    }

    private static Path mccPath(Path regionFile, int chunkX, int chunkZ) {
        return regionFile.resolveSibling("c." + chunkX + "." + chunkZ + ".mcc");
    }

    private static boolean isLegacyWholeFileRegion(String key) {
        return key.endsWith(".mca") || key.endsWith(".mcc");
    }

    private static int[] parseRegionName(String key) throws IOException {
        String name = key.substring(key.lastIndexOf('/') + 1);
        Matcher matcher = REGION_NAME.matcher(name);
        if (!matcher.matches()) {
            throw new IOException("chunk-backed key is not an .mca region: " + key);
        }
        try {
            return new int[]{Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2))};
        } catch (NumberFormatException e) {
            throw new IOException("bad region name: " + key, e);
        }
    }

    private static void copyPermissions(Path source, Path target, Report report) {
        try {
            PosixFileAttributeView view = Files.getFileAttributeView(source,
                    PosixFileAttributeView.class);
            if (view == null) {
                return;
            }
            Files.setPosixFilePermissions(target, view.readAttributes().permissions());
        } catch (UnsupportedOperationException e) {
            // non-POSIX filesystem; temp file permissions are the best available
        } catch (IOException e) {
            report.warn("cannot copy permissions from {}: {}", source, e.toString());
        }
    }

    private record ExternalWrite(Path temp, Path target) {
    }

    /** 一次 rollback 的统计。 */
    public static final class Report {
        private int keysScanned;
        private int filesRestored;
        private int filesDeleted;
        private int filesSkipped;
        private int regionsRebuilt;
        private long chunksWritten;
        private long chunksRemoved;
        private long chunksPreserved;
        private long chunksDropped;
        private long externalChunks;
        private int warnings;

        public int keysScanned() {
            return keysScanned;
        }

        public int filesRestored() {
            return filesRestored;
        }

        public int filesDeleted() {
            return filesDeleted;
        }

        public int filesSkipped() {
            return filesSkipped;
        }

        public int regionsRebuilt() {
            return regionsRebuilt;
        }

        public long chunksWritten() {
            return chunksWritten;
        }

        public long chunksRemoved() {
            return chunksRemoved;
        }

        public long chunksPreserved() {
            return chunksPreserved;
        }

        public long chunksDropped() {
            return chunksDropped;
        }

        public long externalChunks() {
            return externalChunks;
        }

        public int warnings() {
            return warnings;
        }

        private void warn(String message, Object... args) {
            warnings++;
            Log.LOGGER.warn(message, args);
        }
    }
}
