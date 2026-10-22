package io.github.linkfgfgui.minimalbackup.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** E6: 分支解析、ABSENT、PASSTHROUGH、哈希校验。 */
final class TestResolver {
    private TestResolver() {
    }

    static void run() throws Exception {
        Path dir = TestSupport.tempDir("resolver");
        try {
            Path snapshotRoot = dir.resolve("minimalbackup_snapshots");
            Files.createDirectories(snapshotRoot);
            Timeline timeline = Timeline.empty();
            timeline.add(TestSupport.snap("S0", 1, ""));
            timeline.add(TestSupport.snap("S1", 2, "S0"));
            timeline.add(TestSupport.snap("S2", 3, "S0"));
            timeline.add(TestSupport.snap("S3", 4, "S1"));
            timeline.setHead("S3");
            timeline.save(snapshotRoot.resolve(VaultPaths.INDEX_FILE_NAME));

            byte[] v0 = TestSupport.bytes(2048, 10);
            byte[] v1 = TestSupport.mutate(v0, 11);
            byte[] v2 = TestSupport.mutate(v1, 12);
            byte[] v3 = TestSupport.mutate(v2, 13);
            Path level = VaultPaths.zpackFor(snapshotRoot, "level.dat");
            BlockTable levelTable = BlockTable.empty();
            long levelCache = -1L;
            levelCache = BlockFile.append(level, levelTable, List.of(TestSupport.fullBlock(1, 1, 0L, v0)), levelCache, true);
            levelCache = BlockFile.append(level, levelTable, List.of(TestSupport.deltaBlock(2, 1, 2, 0L, v1, v0)), levelCache, true);
            levelCache = BlockFile.append(level, levelTable, List.of(TestSupport.deltaBlock(3, 2, 3, 0L, v2, v1)), levelCache, true);
            levelCache = BlockFile.append(level, levelTable, List.of(TestSupport.deltaBlock(4, 3, 4, 0L, v3, v2)), levelCache, true);
            timeline.putCacheOffset("level.dat", levelCache);

            byte[] first = TestSupport.bytes(4096, 20);
            Path region = VaultPaths.zpackFor(snapshotRoot, "region/r.0.0.mca");
            BlockTable regionTable = BlockTable.empty();
            long regionCache = BlockFile.append(region, regionTable,
                    List.of(TestSupport.fullBlock(1, 2, 7L, Block.TYPE_CHUNK, first)), -1L, true);
            timeline.putCacheOffset("region/r.0.0.mca", regionCache);

            byte[] old = TestSupport.text("old");
            byte[] fresh = TestSupport.text("new");
            Path absent = VaultPaths.zpackFor(snapshotRoot, "data/absent.dat");
            BlockTable absentTable = BlockTable.empty();
            long absentCache = -1L;
            absentCache = BlockFile.append(absent, absentTable, List.of(TestSupport.fullBlock(1, 1, 0L, old)), absentCache, true);
            absentCache = BlockFile.append(absent, absentTable, List.of(TestSupport.absentBlock(2, 2, 0L)), absentCache, true);
            absentCache = BlockFile.append(absent, absentTable, List.of(TestSupport.fullBlock(3, 3, 0L, fresh)), absentCache, true);
            timeline.putCacheOffset("data/absent.dat", absentCache);

            Resolver resolver = new Resolver(snapshotRoot, timeline);
            present(resolver, "level.dat", 0L, "S0", v0, "S0 branch root");
            present(resolver, "level.dat", 0L, "S1", v1, "S1 branch");
            present(resolver, "level.dat", 0L, "S2", v2, "S2 dead branch");
            present(resolver, "level.dat", 0L, "S3", v3, "S3 child of S1");
            present(resolver, "region/r.0.0.mca", 7L, "S0", first, "older than first block");
            present(resolver, "region/r.0.0.mca", 7L, "S3", first, "region at S3");
            presentTyped(resolver, "region/r.0.0.mca", 7L, Block.TYPE_CHUNK, "S3", first,
                    "typed chunk at S3");
            TestSupport.eq(Resolver.Kind.PASSTHROUGH,
                    resolver.resolve("region/r.0.0.mca", 7L, Block.TYPE_FILE, "S3").kind(),
                    "region has no file block");

            byte[] chunk0 = TestSupport.chunkNbt(256, 30);
            byte[] fileRaw = TestSupport.bytes(512, 31);
            byte[] chunk1 = TestSupport.chunkNbt(256, 32);
            Path mixed = VaultPaths.zpackFor(snapshotRoot, "region/r.9.9.mca");
            BlockTable mixedTable = BlockTable.empty();
            long mixedCache = BlockFile.append(mixed, mixedTable, List.of(
                    TestSupport.fullBlock(1, 1, 0L, Block.TYPE_CHUNK, chunk0)), -1L, true);
            mixedCache = BlockFile.append(mixed, mixedTable, List.of(
                    TestSupport.fullBlock(2, 2, 0L, Block.TYPE_FILE, fileRaw)), mixedCache, true);
            mixedCache = BlockFile.append(mixed, mixedTable, List.of(
                    TestSupport.fullBlock(3, 2, 0L, Block.TYPE_CHUNK, chunk1)), mixedCache, true);
            timeline.putCacheOffset("region/r.9.9.mca", mixedCache);
            presentTyped(resolver, "region/r.9.9.mca", 0L, Block.TYPE_CHUNK, "S1", chunk1,
                    "chunk (0,0) ignores file block");
            presentTyped(resolver, "region/r.9.9.mca", 0L, Block.TYPE_FILE, "S1", fileRaw,
                    "file block ignores chunk (0,0)");
            present(resolver, "region/r.9.9.mca", 0L, "S1", chunk1,
                    "wildcard newest block wins");

            present(resolver, "data/absent.dat", 0L, "S0", old, "before absence");
            TestSupport.eq(Resolver.Kind.ABSENT, resolver.resolve("data/absent.dat", 0L, "S1").kind(),
                    "absent at S1");
            present(resolver, "data/absent.dat", 0L, "S2", fresh, "recreated on S2 branch");
            TestSupport.eq(Resolver.Kind.ABSENT, resolver.resolve("data/absent.dat", 0L, "S3").kind(),
                    "S3 sees S1 absence");

            TestSupport.eq(Resolver.Kind.PASSTHROUGH,
                    resolver.resolve("missing.dat", 0L, "S3").kind(), "missing file");
            TestSupport.eq(Resolver.Kind.PASSTHROUGH,
                    resolver.resolve("level.dat", 99L, "S3").kind(), "unknown sub-target");
            TestSupport.eq(Resolver.Kind.PASSTHROUGH,
                    resolver.resolve("session.lock", 0L, "S3").kind(), "excluded key");
            TestSupport.expectIOException(() -> resolver.resolve("level.dat", 0L, "nope"), "unknown snapshot");

            Path corrupt = VaultPaths.zpackFor(snapshotRoot, "corrupt.dat");
            Files.copy(level, corrupt);
            byte[] corruptBytes = Files.readAllBytes(corrupt);
            int payload = (int) levelTable.byNumber(2).offset() + Block.HEADER_SIZE + 3;
            corruptBytes[payload] ^= 0x7F;
            Files.write(corrupt, corruptBytes);
            Resolver corruptResolver = new Resolver(snapshotRoot, timeline);
            TestSupport.expectIOException(
                    () -> corruptResolver.resolve("corrupt.dat", 0L, "S1"), "hash mismatch");
        } finally {
            TestSupport.deleteTree(dir);
        }
    }

    private static void present(Resolver resolver, String key, long subTarget, String slot,
                                byte[] expected, String message) throws IOException {
        Resolver.Result result = resolver.resolve(key, subTarget, slot);
        TestSupport.eq(Resolver.Kind.PRESENT, result.kind(), message + " kind");
        TestSupport.bytesEq(expected, result.data(), message);
    }

    private static void presentTyped(Resolver resolver, String key, long subTarget,
                                     int subTargetType, String slot, byte[] expected,
                                     String message) throws IOException {
        Resolver.Result result = resolver.resolve(key, subTarget, subTargetType, slot);
        TestSupport.eq(Resolver.Kind.PRESENT, result.kind(), message + " kind");
        TestSupport.bytesEq(expected, result.data(), message);
    }
}
