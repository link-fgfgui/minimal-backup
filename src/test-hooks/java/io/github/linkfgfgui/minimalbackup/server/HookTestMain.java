package io.github.linkfgfgui.minimalbackup.server;

import io.github.linkfgfgui.minimalbackup.MinimalBackup;
import java.nio.file.Files;
import java.nio.file.Path;

/** Runs the hook-side tests against a real Minecraft artifact, outside the game. */
public final class HookTestMain {
    private HookTestMain() {
    }

    public static void main(String[] args) throws Exception {
        Path gameDir = Files.createTempDirectory("minimalbackup-hooks-");
        MinimalBackup.init(gameDir);
        long start = System.nanoTime();
        TestCowFallback.run(gameDir);
        System.out.println("[PASS] H3 CowFallback");
        TestReadCapture.run(gameDir);
        System.out.println("[PASS] H4 ReadCapture");
        TestRestoreGate.run(gameDir);
        System.out.println("[PASS] H5 RestoreGate");
        TestHookCoverage.run(gameDir);
        System.out.println("[PASS] H6 HookCoverage");
        TestRestoreBranch.run(gameDir);
        System.out.println("[PASS] H7 RestoreBranch");
        TestJsonWiring.run(gameDir);
        System.out.println("[PASS] H8 JsonWiring");
        System.out.println("HOOK TESTS PASSED in " + ((System.nanoTime() - start) / 1_000_000L) + " ms");
    }
}
