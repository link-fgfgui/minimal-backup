package io.github.linkfgfgui.minimalbackup.engine;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** E2/E3/E4: 块文件布局、扫描停止位置、缓存命中与回退。 */
final class TestBlockFile {
    private static final int CACHE_MAGIC = 0x4D424931;

    private TestBlockFile() {
    }

    static void run() throws Exception {
        Path dir = TestSupport.tempDir("blockfile");
        try {
            layout(dir);
            scanStops(dir);
            cache(dir);
            largeCache(dir);
        } finally {
            TestSupport.deleteTree(dir);
        }
    }

    private static void layout(Path dir) throws IOException {
        Path file = dir.resolve("layout.zpack");
        byte[] raw1 = TestSupport.bytes(1024, 1);
        byte[] raw2 = TestSupport.bytes(1024, 2);
        BlockFile.NewBlock nb1 = full(1, 1, 0L, raw1);
        BlockFile.NewBlock nb2 = delta(2, 1, 2, 0L, raw2, raw1);
        BlockTable table = BlockTable.empty();
        long cache = BlockFile.append(file, table, List.of(nb1, nb2), -1L, true);
        TestSupport.eq(8L + nb1.block().totalLength() + nb2.block().totalLength(), cache, "cache offset");

        byte[] all = Files.readAllBytes(file);
        ByteBuffer buf = ByteBuffer.wrap(all).order(ByteOrder.BIG_ENDIAN);
        TestSupport.eq(BlockFile.FILE_MAGIC, buf.getInt(0), "file magic");
        TestSupport.eq(BlockFile.FILE_VERSION, buf.getShort(4) & 0xFFFF, "file version");
        TestSupport.eq(0, buf.getShort(6) & 0xFFFF, "file flags");

        TestSupport.eq(Block.MAGIC, buf.getInt(8), "block1 magic");
        TestSupport.eq(nb1.block().totalLength(), buf.getInt(12), "block1 total");
        TestSupport.eq(1, buf.getInt(16), "block1 number");
        TestSupport.eq(0, buf.getInt(20), "block1 dictionary");
        TestSupport.eq(1, buf.getInt(24), "block1 generation");
        TestSupport.eq(0L, buf.getLong(28), "block1 sub-target");
        TestSupport.eq(Block.TYPE_FILE, buf.get(36) & 0xFF, "block1 type");
        TestSupport.eq(Block.MODE_FULL, buf.get(37) & 0xFF, "block1 mode");
        TestSupport.eq(raw1.length, buf.getInt(38), "block1 original length");
        TestSupport.eq(Xxh64.hash(raw1), buf.getLong(42), "block1 hash");

        int off2 = 8 + nb1.block().totalLength();
        TestSupport.eq(Block.MAGIC, buf.getInt(off2), "block2 magic");
        TestSupport.eq(2, buf.getInt(off2 + 8), "block2 number");
        TestSupport.eq(1, buf.getInt(off2 + 12), "block2 dictionary");
        TestSupport.eq(2, buf.getInt(off2 + 16), "block2 generation");
        TestSupport.eq(Block.MODE_DELTA, buf.get(off2 + 29) & 0xFF, "block2 mode");
        TestSupport.eq(Xxh64.hash(raw2), buf.getLong(off2 + 34), "block2 hash");

        TestSupport.eq(CACHE_MAGIC, buf.getInt((int) cache), "cache magic");
        TestSupport.eq(2, buf.getInt((int) cache + 4), "cache count");
        long cacheLength = BlockFile.cacheLength(file, cache);
        TestSupport.check(cacheLength > 20L && cacheLength < 80L, "cache compact");
        TestSupport.eq((long) buf.getInt((int) cache + 8), cacheLength, "cache total");

        BlockFile.Scan scan = BlockFile.scan(file);
        TestSupport.eq(2, scan.blocks().size(), "scan count");
        TestSupport.eq(cache, scan.blockAreaEnd(), "scan block end");
        TestSupport.eq(cache + cacheLength, scan.fileLength(), "scan file length");

        BlockTable loaded = BlockFile.loadTable(file, cache);
        TestSupport.eq(2, loaded.blocks().size(), "cache load count");
        TestSupport.bytesEq(raw2, BlockFile.reconstruct(file, loaded, loaded.byNumber(2)), "reconstruct");
        TestSupport.bytesEq(nb1.payload(), BlockFile.readPayload(file, loaded.byNumber(1)), "payload read");
        TestSupport.bytesEq(raw1, BlockFile.reconstruct(file, loaded, loaded.byNumber(1)), "reconstruct full");
    }

    private static void scanStops(Path dir) throws IOException {
        byte[] raw1 = TestSupport.bytes(512, 3);
        byte[] raw2 = TestSupport.bytes(512, 4);

        Path half = dir.resolve("half.zpack");
        BlockTable halfTable = BlockTable.empty();
        BlockFile.append(half, halfTable, List.of(full(1, 1, 0L, raw1)), -1L, true);
        long halfEnd = halfTable.blockAreaEnd();
        BlockFile.truncate(half, halfEnd);
        byte[] partialHeader = full(2, 2, 0L, raw2).block().encodeHeader();
        Files.write(half, partialHeader, StandardOpenOption.APPEND);
        BlockFile.Scan halfScan = BlockFile.scan(half);
        TestSupport.eq(1, halfScan.blocks().size(), "half block not counted");
        TestSupport.eq(halfEnd, halfScan.blockAreaEnd(), "half block stop position");
        TestSupport.check(halfScan.fileLength() > halfScan.blockAreaEnd(), "half block remains");

        Path gap = dir.resolve("gap.zpack");
        BlockTable gapTable = BlockTable.empty();
        BlockFile.append(gap, gapTable, List.of(full(1, 1, 0L, raw1)), -1L, true);
        long gapEnd = gapTable.blockAreaEnd();
        BlockFile.truncate(gap, gapEnd);
        BlockFile.NewBlock jumping = full(5, 2, 0L, raw2);
        Files.write(gap, jumping.block().encodeHeader(), StandardOpenOption.APPEND);
        Files.write(gap, jumping.payload(), StandardOpenOption.APPEND);
        BlockFile.Scan gapScan = BlockFile.scan(gap);
        TestSupport.eq(1, gapScan.blocks().size(), "number gap not counted");
        TestSupport.eq(gapEnd, gapScan.blockAreaEnd(), "number gap stop position");

        Path shortFile = dir.resolve("short.zpack");
        TestSupport.write(shortFile, new byte[]{1, 2, 3, 4});
        TestSupport.expectIOException(() -> BlockFile.scan(shortFile), "short header");

        Path badMagic = dir.resolve("bad.zpack");
        TestSupport.write(badMagic, new byte[]{0, 0, 0, 0, 0, 2, 0, 0});
        TestSupport.expectIOException(() -> BlockFile.scan(badMagic), "bad magic");
    }

    private static void cache(Path dir) throws IOException {
        byte[] raw1 = TestSupport.bytes(768, 5);
        byte[] raw2 = TestSupport.bytes(768, 6);
        byte[] raw3 = TestSupport.bytes(768, 7);
        Path file = dir.resolve("cache.zpack");
        BlockTable table = BlockTable.empty();
        long cache1 = BlockFile.append(file, table,
                List.of(full(1, 1, 0L, raw1), delta(2, 1, 2, 0L, raw2, raw1)), -1L, true);
        TestSupport.eq(2, BlockFile.loadTable(file, cache1).blocks().size(), "cache hit");

        byte[] bytes = Files.readAllBytes(file);
        bytes[(int) cache1 + 20] ^= 0x5A;
        Files.write(file, bytes);
        TestSupport.check(BlockFile.readCache(file, cache1) == null, "corrupt cache rejected");
        TestSupport.eq(2, BlockFile.loadTable(file, cache1).blocks().size(), "corrupt cache fallback");

        Path overwrite = dir.resolve("overwrite.zpack");
        BlockTable table2 = BlockTable.empty();
        long cache2 = BlockFile.append(overwrite, table2,
                List.of(full(1, 1, 0L, raw1), delta(2, 1, 2, 0L, raw2, raw1)), -1L, true);
        long cache3 = BlockFile.append(overwrite, table2, List.of(full(3, 3, 0L, raw3)), cache2, true);
        TestSupport.check(cache3 > cache2, "new cache after overwritten cache");
        TestSupport.check(BlockFile.readCache(overwrite, cache2) == null, "old cache overwritten");
        TestSupport.eq(3, BlockFile.loadTable(overwrite, cache2).blocks().size(), "overwritten cache fallback");
        TestSupport.eq(3, BlockFile.loadTable(overwrite, cache3).blocks().size(), "new cache hit");
        TestSupport.eq(3, BlockFile.loadTable(overwrite, -1L).blocks().size(), "no cache scan");
    }

    /** A many-block cache must stay well below the old 30-byte entry size. */
    private static void largeCache(Path dir) throws IOException {
        Path file = dir.resolve("large.zpack");
        BlockTable table = BlockTable.empty();
        int count = 300;
        List<BlockFile.NewBlock> blocks = new ArrayList<>(count);
        byte[][] raws = new byte[count][];
        for (int i = 0; i < count; i++) {
            raws[i] = TestSupport.bytes(96, 1000 + i);
            blocks.add(full(i + 1, 1, i, raws[i]));
        }
        long cache = BlockFile.append(file, table, blocks, -1L, true);
        long cacheLength = BlockFile.cacheLength(file, cache);
        TestSupport.check(cacheLength > 20L, "large cache present");
        TestSupport.check(cacheLength < 20L + 3L * count, "cache compact");
        TestSupport.check(cacheLength < 20L + 30L * count, "cache smaller than 30-byte entries");
        BlockTable loaded = BlockFile.loadTable(file, cache);
        TestSupport.eq(count, loaded.blocks().size(), "large cache count");
        TestSupport.bytesEq(raws[count - 1],
                BlockFile.reconstruct(file, loaded, loaded.byNumber(count)), "large reconstruct");
    }

    private static BlockFile.NewBlock full(int number, int generation, long subTarget, byte[] raw)
            throws IOException {
        byte[] payload = ZstdDelta.compressFull(raw, TestSupport.SETTINGS.zstdLevel());
        Block block = Block.create(number, 0, generation, subTarget, Block.TYPE_FILE,
                Block.MODE_FULL, raw.length, Xxh64.hash(raw), payload.length);
        return new BlockFile.NewBlock(block, payload);
    }

    private static BlockFile.NewBlock delta(int number, int dictionary, int generation, long subTarget,
                                            byte[] raw, byte[] base) throws IOException {
        byte[] payload = ZstdDelta.compressDelta(raw, base, TestSupport.SETTINGS.zstdLevel(),
                TestSupport.SETTINGS.zstdLong(), TestSupport.SETTINGS.zstdLongWindowLog());
        Block block = Block.create(number, dictionary, generation, subTarget, Block.TYPE_FILE,
                Block.MODE_DELTA, raw.length, Xxh64.hash(raw), payload.length);
        return new BlockFile.NewBlock(block, payload);
    }
}
