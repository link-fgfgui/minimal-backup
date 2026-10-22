package io.github.linkfgfgui.minimalbackup.engine;

/** 顺序执行全部引擎测试；任一失败抛 AssertionError 并以非零码退出。 */
public final class TestMain {
    private TestMain() {
    }

    public static void main(String[] args) throws Exception {
        long start = System.nanoTime();
        TestVaultPaths.run();
        System.out.println("[PASS] E1 VaultPaths");
        TestBlockFile.run();
        System.out.println("[PASS] E2/E3/E4 BlockFile");
        TestTimeline.run();
        System.out.println("[PASS] E5 Snapshot/Timeline");
        TestResolver.run();
        System.out.println("[PASS] E6 Resolver");
        TestCheckpoint.run();
        System.out.println("[PASS] E7 Checkpoint");
        TestCompactor.run();
        System.out.println("[PASS] E8 Compactor");
        TestSelfHealing.run();
        System.out.println("[PASS] E9 SelfHealing");
        TestEndToEnd.run();
        System.out.println("[PASS] E10 End-to-end + CLI");
        TestJsonStore.run();
        System.out.println("[PASS] E11 JsonStore");
        TestRegionRollback.run();
        System.out.println("[PASS] E12 Anvil region rollback");
        TestNbt.run();
        System.out.println("[PASS] E13 NBT canonicalise/align");
        TestNbtFixtures.run();
        System.out.println("[PASS] E14 NBT real fixtures");
        TestCodecApi.run();
        System.out.println("[PASS] E15 Codec API");
        io.github.linkfgfgui.minimalbackup.config.TestConfigSchema.run();
        System.out.println("[PASS] H0 ConfigSchema");
        io.github.linkfgfgui.minimalbackup.server.TestSnapshotKeys.run();
        System.out.println("[PASS] H1 SnapshotKeys");
        io.github.linkfgfgui.minimalbackup.server.TestChangeSet.run();
        System.out.println("[PASS] H2 ChangeSet");
        io.github.linkfgfgui.minimalbackup.ui.TestProtocol.run();
        System.out.println("[PASS] H3 Protocol");
        System.out.println("ALL TESTS PASSED in " + ((System.nanoTime() - start) / 1_000_000L) + " ms");
    }
}
