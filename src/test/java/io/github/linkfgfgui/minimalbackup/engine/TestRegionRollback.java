package io.github.linkfgfgui.minimalbackup.engine;

import io.github.linkfgfgui.minimalbackup.engine.anvil.AnvilRegion;
import io.github.linkfgfgui.minimalbackup.engine.anvil.AnvilRegionWriter;
import io.github.linkfgfgui.minimalbackup.engine.cli.Main;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.InflaterInputStream;

/** E12: CLI rollback 把区块块重建为 Anvil .mca。 */
final class TestRegionRollback {
    private TestRegionRollback() {
    }

    static void run() throws Exception {
        coordinateAndHeaderTests();
        writerTests();
        rebuildScenario();
        truncatedBaseScenario();
        invalidOverlayScenario();
        nonRegionChunkKeyScenario();
    }

    private static void coordinateAndHeaderTests() {
        TestSupport.eq(0, AnvilRegion.localIndex(0, 0), "local 0,0");
        TestSupport.eq(31, AnvilRegion.localIndex(-1, 0), "local -1,0");
        TestSupport.eq(1023, AnvilRegion.localIndex(-1, -1), "local -1,-1");
        long subTarget = AnvilRegion.chunkSubTarget(-3, 5);
        TestSupport.eq(-3, AnvilRegion.chunkX(subTarget), "chunk x round trip");
        TestSupport.eq(5, AnvilRegion.chunkZ(subTarget), "chunk z round trip");
        TestSupport.eq(1, AnvilRegion.seconds(1999L), "seconds floor");
        TestSupport.eq(0, AnvilRegion.seconds(-1L), "seconds clamp low");
        TestSupport.eq(Integer.MAX_VALUE, AnvilRegion.seconds(Long.MAX_VALUE), "seconds clamp high");

        byte[] header = new byte[AnvilRegion.HEADER_BYTES];
        ByteBuffer buf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(0, AnvilRegion.packLocation(1, 1));
        buf.putInt(4, AnvilRegion.packLocation(3, 1));
        buf.putInt(8, AnvilRegion.packLocation(2, 1));
        buf.putInt(AnvilRegion.SECTOR_BYTES, 77);
        AnvilRegion parsed = AnvilRegion.parse(header, 12288L);
        TestSupport.check(!parsed.present(0), "offset below 2 is invalid");
        TestSupport.check(!parsed.present(1), "location beyond file is invalid");
        TestSupport.check(parsed.present(2), "header-only first data sector is valid");
        TestSupport.eq(2, parsed.invalidCount(), "invalid location count");
        TestSupport.eq(77, parsed.timestamp(0), "timestamp parse");

        TestSupport.expectIllegalArgument(() -> AnvilRegion.packLocation(0, 1),
                "offset/count mismatch");
        TestSupport.expectIllegalArgument(() -> AnvilRegion.packLocation(2, 256),
                "sector count too large");
    }

    private static void writerTests() throws Exception {
        Path dir = TestSupport.tempDir("anvil-writer");
        try {
            Path file = dir.resolve("r.0.0.mca");
            byte[] internal = AnvilRegion.encodeChunk(2, TestSupport.bytes(100, 1));
            try (AnvilRegionWriter writer = new AnvilRegionWriter(file)) {
                writer.writeChunk(0, internal, 5);
                writer.writeChunk(1, AnvilRegion.externalStub(1), 6);
                writer.finish();
            }
            View view = new View(file);
            TestSupport.check(view.header.present(0), "writer chunk 0");
            TestSupport.eq(2, view.type(0), "writer type");
            TestSupport.eq(5, view.header.timestamp(0), "writer timestamp");
            TestSupport.eq(0x81, view.type(1), "writer external stub");
            TestSupport.eq(0L, Files.size(file) % AnvilRegion.SECTOR_BYTES,
                    "writer sector aligned");
            try (AnvilRegionWriter writer = new AnvilRegionWriter(dir.resolve("dup.mca"))) {
                writer.writeChunk(0, internal, 1);
                try {
                    writer.writeChunk(0, internal, 1);
                    throw new AssertionError("duplicate index accepted");
                } catch (IllegalArgumentException expected) {
                    // expected
                }
            }
        } finally {
            TestSupport.deleteTree(dir);
        }
    }

    private static void rebuildScenario() throws Exception {
        Path dir = TestSupport.tempDir("region-rebuild");
        try {
            Path gameDir = dir.resolve("game");
            Path target = dir.resolve("target");
            Path regionDir = target.resolve("region");
            Files.createDirectories(regionDir);

            byte[] base0 = TestSupport.chunkNbt(128, 10);
            byte[] keep3 = TestSupport.bytes(7000, 12);
            byte[] external5 = TestSupport.bytes(5000, 13);
            Path base = regionDir.resolve("r.0.0.mca");
            try (AnvilRegionWriter writer = new AnvilRegionWriter(base)) {
                writer.writeChunk(AnvilRegion.localIndex(0, 0),
                        AnvilRegion.encodeChunk(3, base0), 123);
                writer.writeChunk(AnvilRegion.localIndex(1, 0),
                        AnvilRegion.externalStub(1), 123);
                writer.writeChunk(AnvilRegion.localIndex(3, 0),
                        AnvilRegion.encodeChunk(3, keep3), 123);
                writer.writeChunk(AnvilRegion.localIndex(5, 0),
                        AnvilRegion.externalStub(1), 123);
                writer.finish();
            }
            TestSupport.write(regionDir.resolve("c.1.0.mcc"), TestSupport.bytes(1000, 15));
            TestSupport.write(regionDir.resolve("c.5.0.mcc"), external5);
            byte[] legacyTarget = TestSupport.bytes(64, 14);
            TestSupport.write(regionDir.resolve("r.9.9.mca"), legacyTarget);

            byte[] new0 = TestSupport.chunkNbt(2048, 20);
            byte[] new2 = TestSupport.chunkNbt(1024, 21);
            byte[] huge = TestSupport.chunkNbt(1_200_000, 22);
            byte[] neg = TestSupport.chunkNbt(512, 23);
            byte[] entity = TestSupport.chunkNbt(768, 24);
            byte[] poi = TestSupport.chunkNbt(768, 25);
            byte[] legacyVault = TestSupport.bytes(256, 26);

            Path vault = VaultPaths.vaultRoot(gameDir, "World");
            Path snapshotRoot = VaultPaths.snapshotRoot(vault);
            Timeline timeline = Timeline.load(VaultPaths.indexFile(vault));
            Checkpoint checkpoint = new Checkpoint(snapshotRoot, timeline, TestSupport.SETTINGS);
            Checkpoint.ChangeSet changes = new Checkpoint.ChangeSet()
                    .putNew("region/r.0.0.mca", AnvilRegion.chunkSubTarget(0, 0),
                            Block.TYPE_CHUNK, new0)
                    .absent("region/r.0.0.mca", AnvilRegion.chunkSubTarget(1, 0),
                            Block.TYPE_CHUNK)
                    .putNew("region/r.0.0.mca", AnvilRegion.chunkSubTarget(2, 0),
                            Block.TYPE_CHUNK, new2)
                    .putNew("region/r.0.0.mca", AnvilRegion.chunkSubTarget(4, 0),
                            Block.TYPE_CHUNK, huge)
                    .putNew("region/r.-1.-1.mca", AnvilRegion.chunkSubTarget(-1, -1),
                            Block.TYPE_CHUNK, neg)
                    .putNew("entities/r.0.0.mca", AnvilRegion.chunkSubTarget(0, 0),
                            Block.TYPE_CHUNK, entity)
                    .putNew("poi/r.0.0.mca", AnvilRegion.chunkSubTarget(0, 0),
                            Block.TYPE_CHUNK, poi)
                    .putNew("region/r.9.9.mca", 0L, Block.TYPE_FILE, legacyVault);
            Snapshot snapshot = checkpoint.create(changes, new Checkpoint.SnapshotInfo()
                    .displayName("region").source("test"));

            Path regionZpack = VaultPaths.zpackFor(snapshotRoot, "region/r.0.0.mca");
            Long cache = timeline.cacheOffset("region/r.0.0.mca");
            BlockTable table = BlockFile.loadTable(regionZpack, cache);
            long newCache = BlockFile.append(regionZpack, table, List.of(
                    TestSupport.fullBlock(table.nextBlockNumber(), snapshot.generation(), 0L,
                            Block.TYPE_FILE, TestSupport.bytes(64, 27))), cache, true);
            timeline.putCacheOffset("region/r.0.0.mca", newCache);
            timeline.save(VaultPaths.indexFile(vault));

            TestSupport.eq(0, Main.run(new String[]{"rollback", gameDir.toString(), "World",
                    snapshot.slot(), target.toString()}), "rollback region vault");
            TestSupport.check(!Files.exists(regionDir.resolve(".mb-tmp")), "no temp file");

            View region = new View(regionDir.resolve("r.0.0.mca"));
            TestSupport.check(region.header.present(0), "chunk 0 present");
            TestSupport.eq(2, region.type(0), "chunk 0 zlib");
            TestSupport.bytesEq(new0, region.nbt(0), "chunk 0 overridden");
            TestSupport.check(!region.header.present(1), "chunk 1 removed");
            TestSupport.check(!Files.exists(regionDir.resolve("c.1.0.mcc")),
                    "removed external chunk .mcc deleted");
            TestSupport.eq(2, region.type(2), "chunk 2 zlib");
            TestSupport.bytesEq(new2, region.nbt(2), "chunk 2 added");
            TestSupport.eq(3, region.type(3), "untouched chunk keeps type");
            TestSupport.bytesEq(keep3, region.raw(3), "untouched chunk raw copy");
            TestSupport.eq(123, region.header.timestamp(3), "untouched timestamp");
            TestSupport.eq(0x81, region.type(5), "untouched external stub");
            TestSupport.bytesEq(external5, Files.readAllBytes(regionDir.resolve("c.5.0.mcc")),
                    "untouched external payload");
            TestSupport.eq(0x82, region.type(4), "oversized chunk external stub");
            TestSupport.bytesEq(huge, inflate(Files.readAllBytes(
                    regionDir.resolve("c.4.0.mcc"))), "oversized chunk payload");
            TestSupport.bytesEq(legacyTarget,
                    Files.readAllBytes(regionDir.resolve("r.9.9.mca")),
                    "legacy whole-file region skipped");
            TestSupport.eq(0L, Files.size(regionDir.resolve("r.0.0.mca"))
                    % AnvilRegion.SECTOR_BYTES, "region sector aligned");

            View negative = new View(regionDir.resolve("r.-1.-1.mca"));
            TestSupport.check(negative.header.present(1023), "negative chunk index");
            TestSupport.bytesEq(neg, negative.nbt(1023), "negative chunk payload");
            View entities = new View(target.resolve("entities/r.0.0.mca"));
            TestSupport.bytesEq(entity, entities.nbt(0), "entities region rebuilt");
            View poiRegion = new View(target.resolve("poi/r.0.0.mca"));
            TestSupport.bytesEq(poi, poiRegion.nbt(0), "poi region rebuilt");

            try (var walk = Files.walk(target)) {
                TestSupport.check(walk.noneMatch(path -> path.getFileName().toString()
                        .endsWith(AtomicFiles.TEMP_SUFFIX)), "no rollback temp files");
            }
        } finally {
            TestSupport.deleteTree(dir);
        }
    }

    private static void truncatedBaseScenario() throws Exception {
        Path dir = TestSupport.tempDir("region-truncated");
        try {
            Path gameDir = dir.resolve("game");
            Path target = dir.resolve("target");
            TestSupport.write(target.resolve("region/r.0.0.mca"), TestSupport.bytes(100, 30));

            Path vault = VaultPaths.vaultRoot(gameDir, "World");
            Path snapshotRoot = VaultPaths.snapshotRoot(vault);
            Timeline timeline = Timeline.load(VaultPaths.indexFile(vault));
            Checkpoint checkpoint = new Checkpoint(snapshotRoot, timeline, TestSupport.SETTINGS);
            byte[] nbt = TestSupport.chunkNbt(512, 31);
            Checkpoint.ChangeSet changes = new Checkpoint.ChangeSet()
                    .putNew("region/r.0.0.mca", AnvilRegion.chunkSubTarget(0, 0),
                            Block.TYPE_CHUNK, nbt);
            Snapshot snapshot = checkpoint.create(changes, new Checkpoint.SnapshotInfo().source("test"));

            Rollback.Report report = new Rollback(snapshotRoot, timeline, target).run(snapshot);
            TestSupport.eq(1, report.regionsRebuilt(), "truncated base rebuilt");
            TestSupport.check(report.warnings() > 0, "truncated base warned");
            View region = new View(target.resolve("region/r.0.0.mca"));
            TestSupport.bytesEq(nbt, region.nbt(0), "truncated base overlay");
        } finally {
            TestSupport.deleteTree(dir);
        }
    }

    private static void invalidOverlayScenario() throws Exception {
        Path dir = TestSupport.tempDir("region-invalid");
        try {
            Path gameDir = dir.resolve("game");
            Path target = dir.resolve("target");
            Path base = target.resolve("region/r.0.0.mca");
            try (AnvilRegionWriter writer = new AnvilRegionWriter(base)) {
                writer.finish();
            }
            byte[] before = Files.readAllBytes(base);

            Path vault = VaultPaths.vaultRoot(gameDir, "World");
            Path snapshotRoot = VaultPaths.snapshotRoot(vault);
            Timeline timeline = Timeline.load(VaultPaths.indexFile(vault));
            Checkpoint checkpoint = new Checkpoint(snapshotRoot, timeline, TestSupport.SETTINGS);
            Checkpoint.ChangeSet changes = new Checkpoint.ChangeSet()
                    .putNew("region/r.0.0.mca", AnvilRegion.chunkSubTarget(0, 0),
                            Block.TYPE_CHUNK, TestSupport.bytes(64, 32));
            Snapshot snapshot = checkpoint.create(changes, new Checkpoint.SnapshotInfo().source("test"));
            TestSupport.expectIOException(
                    () -> new Rollback(snapshotRoot, timeline, target).run(snapshot),
                    "non-NBT chunk rejected");
            TestSupport.bytesEq(before, Files.readAllBytes(base), "base left untouched");
        } finally {
            TestSupport.deleteTree(dir);
        }
    }

    private static void nonRegionChunkKeyScenario() throws Exception {
        Path dir = TestSupport.tempDir("region-bad-key");
        try {
            Path gameDir = dir.resolve("game");
            Path target = dir.resolve("target");
            Path vault = VaultPaths.vaultRoot(gameDir, "World");
            Path snapshotRoot = VaultPaths.snapshotRoot(vault);
            Timeline timeline = Timeline.load(VaultPaths.indexFile(vault));
            Checkpoint checkpoint = new Checkpoint(snapshotRoot, timeline, TestSupport.SETTINGS);
            Checkpoint.ChangeSet changes = new Checkpoint.ChangeSet()
                    .putNew("bad/chunk.bin", AnvilRegion.chunkSubTarget(0, 0),
                            Block.TYPE_CHUNK, TestSupport.chunkNbt(64, 33));
            Snapshot snapshot = checkpoint.create(changes, new Checkpoint.SnapshotInfo().source("test"));
            TestSupport.expectIOException(
                    () -> new Rollback(snapshotRoot, timeline, target).run(snapshot),
                    "chunk key without .mca rejected");
        } finally {
            TestSupport.deleteTree(dir);
        }
    }

    private static byte[] inflate(byte[] compressed) throws IOException {
        try (InflaterInputStream in = new InflaterInputStream(
                new ByteArrayInputStream(compressed))) {
            return in.readAllBytes();
        }
    }

    private static final class View {
        private final byte[] bytes;
        private final AnvilRegion header;

        private View(Path file) throws IOException {
            bytes = Files.readAllBytes(file);
            header = AnvilRegion.parse(bytes, bytes.length);
        }

        private int offset(int index) {
            return (int) header.dataOffset(index);
        }

        private int length(int index) {
            return ByteBuffer.wrap(bytes, offset(index), 4)
                    .order(ByteOrder.BIG_ENDIAN).getInt();
        }

        private int type(int index) {
            return bytes[offset(index) + 4] & 0xFF;
        }

        private byte[] raw(int index) {
            int off = offset(index);
            int length = length(index);
            return Arrays.copyOfRange(bytes, off + 5, off + 4 + length);
        }

        private byte[] nbt(int index) throws IOException {
            int type = type(index);
            if ((type & AnvilRegion.EXTERNAL_FLAG) != 0) {
                throw new AssertionError("external chunk has no inline payload");
            }
            byte[] payload = raw(index);
            if (type == AnvilRegion.COMPRESSION_ZLIB) {
                return inflate(payload);
            }
            if (type == 3) {
                return payload;
            }
            throw new AssertionError("unexpected compression type: " + type);
        }
    }
}
