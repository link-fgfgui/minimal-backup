package io.github.linkfgfgui.minimalbackup.engine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** E1: VaultPaths 路径映射、键规范化、排除规则、快照名边界。 */
final class TestVaultPaths {
    private TestVaultPaths() {
    }

    static void run() throws Exception {
        Path game = Paths.get("/tmp/game");
        TestSupport.eq(Paths.get("/tmp/game/mbackups/World"), VaultPaths.vaultRoot(game, "World"), "vault root");
        TestSupport.eq(Paths.get("/tmp/game/mbackups/World/minimalbackup_snapshots"),
                VaultPaths.snapshotRoot(VaultPaths.vaultRoot(game, "World")), "snapshot root");
        TestSupport.eq(Paths.get("/tmp/game/mbackups/World/minimalbackup_snapshots/index.properties"),
                VaultPaths.indexFile(VaultPaths.vaultRoot(game, "World")), "index file");

        TestSupport.eq("a/b", VaultPaths.normalizeKey("a\\b"), "backslash normalization");
        TestSupport.eq("a/b", VaultPaths.normalizeKey("a//b/"), "slash folding");
        TestSupport.eq("a/b", VaultPaths.normalizeKey("./a/./b"), "dot segments");
        TestSupport.eq("region/r.0.0.mca", VaultPaths.normalizeKey("region/r.0.0.mca"), "plain key");
        TestSupport.expectIllegalArgument(() -> VaultPaths.normalizeKey("../a"), "parent");
        TestSupport.expectIllegalArgument(() -> VaultPaths.normalizeKey("a/../b"), "inner parent");
        TestSupport.expectIllegalArgument(() -> VaultPaths.normalizeKey("/a"), "absolute");
        TestSupport.expectIllegalArgument(() -> VaultPaths.normalizeKey("C:/a"), "drive");
        TestSupport.expectIllegalArgument(() -> VaultPaths.normalizeKey(""), "empty");
        TestSupport.expectIllegalArgument(() -> VaultPaths.normalizeKey(null), "null");
        TestSupport.expectIllegalArgument(() -> VaultPaths.normalizeKey("a/b:c"), "colon");

        TestSupport.check(VaultPaths.isExcluded("session.lock"), "session.lock");
        TestSupport.check(VaultPaths.isExcluded("sub/session.lock"), "nested session.lock");
        TestSupport.check(VaultPaths.isExcluded("level.dat_old"), "level.dat_old");
        TestSupport.check(VaultPaths.isExcluded("restore.marker"), "restore.marker");
        TestSupport.check(VaultPaths.isExcluded("x.mb-tmp"), "mb-tmp");
        TestSupport.check(VaultPaths.isExcluded("x.zpack.tmp"), "zpack tmp");
        TestSupport.check(VaultPaths.isExcluded("mbackups/World/x"), "vault path");
        TestSupport.check(VaultPaths.isExcluded("minimalbackup_snapshots/index.properties"), "snapshot root");
        TestSupport.check(!VaultPaths.isExcluded("level.dat"), "level.dat kept");
        TestSupport.check(!VaultPaths.isExcluded("region/r.0.0.mca"), "region kept");

        TestSupport.check(VaultPaths.isValidSnapshotName("S0"), "S0 valid");
        TestSupport.check(VaultPaths.isValidSnapshotName("snap-1_a"), "dash underscore valid");
        TestSupport.check(!VaultPaths.isValidSnapshotName("a.b"), "dot rejected");
        TestSupport.check(!VaultPaths.isValidSnapshotName(""), "empty rejected");
        TestSupport.check(!VaultPaths.isValidSnapshotName("a/b"), "slash rejected");
        TestSupport.check(!VaultPaths.isValidSnapshotName(" a"), "leading space rejected");
        TestSupport.check(!VaultPaths.isValidSnapshotName("a b"), "space rejected");
        TestSupport.check(!VaultPaths.isValidSnapshotName(null), "null rejected");

        TestSupport.expectIllegalArgument(() -> VaultPaths.vaultRoot(game, "a/b"), "world key slash");
        TestSupport.expectIllegalArgument(() -> VaultPaths.vaultRoot(game, ".."), "world key parent");

        Path dir = TestSupport.tempDir("vaultpaths");
        try {
            Path kept = dir.resolve("a/b");
            Files.createDirectories(kept);
            TestSupport.write(kept.resolve("keep"), TestSupport.text("x"));
            Path empty = dir.resolve("x/y/z");
            Files.createDirectories(empty);
            VaultPaths.deleteEmptyDirs(dir);
            TestSupport.check(!Files.exists(empty), "empty dir removed");
            TestSupport.check(!Files.exists(dir.resolve("x")), "empty parent removed");
            TestSupport.check(Files.exists(kept.resolve("keep")), "file kept");
            TestSupport.check(Files.exists(dir), "root kept");
        } finally {
            TestSupport.deleteTree(dir);
        }
    }
}
