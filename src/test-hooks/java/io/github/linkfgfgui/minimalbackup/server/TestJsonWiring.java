package io.github.linkfgfgui.minimalbackup.server;

import io.github.linkfgfgui.minimalbackup.config.ConfigHolder;
import io.github.linkfgfgui.minimalbackup.engine.Snapshot;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** H8: JSON 建点接入、仅 JSON 变化也能建点、首槽 icon、回滚物化。 */
public final class TestJsonWiring {
    private TestJsonWiring() {
    }

    public static void run(Path gameDir) throws Exception {
        Path world = HookTestSupport.world(gameDir, "JsonWorld");
        Files.createDirectories(world.resolve("advancements"));
        Files.createDirectories(world.resolve("stats"));
        byte[] a1 = bytes("{\"a\":1}");
        byte[] b1 = bytes("{\"b\":1}");
        byte[] a2 = bytes("{\"a\":2}");
        byte[] icon = new byte[]{1, 2, 3, 4};
        Files.write(world.resolve("advancements/p.json"), a1);
        Files.write(world.resolve("stats/p.json"), b1);
        Files.write(world.resolve("icon.png"), icon);

        Object previous = ConfigHolder.values().set("backup.copyJson", true);
        try {
            BackupRuntime runtime = BackupRuntime.init(world);
            Path root = runtime.snapshotRoot();

            // 只有 JSON 变化、NBT 缓冲区为空，仍然建点。
            Snapshot s1 = runtime.createSnapshot("test", "json only");
            HookTestSupport.eq("S1", s1.slot(), "json-only snapshot");
            HookTestSupport.check(Files.isRegularFile(root.resolve("advancements/p.json/S1.zst")),
                    "advancements copy");
            HookTestSupport.check(Files.isRegularFile(root.resolve("stats/p.json/S1.zst")),
                    "stats copy");
            HookTestSupport.check(Files.isRegularFile(root.resolve("icon.png")),
                    "icon copied at first snapshot");
            HookTestSupport.eq(icon.length,
                    (int) Files.size(root.resolve("icon.png")), "icon is verbatim");

            // 内容未变：不应产生空快照。
            HookTestSupport.expectIOException(() -> runtime.createSnapshot("test", "nothing"),
                    "no changes captured", "unchanged json does not checkpoint");

            Files.write(world.resolve("advancements/p.json"), a2);
            Snapshot s2 = runtime.createSnapshot("test", "json changed");
            HookTestSupport.eq("S2", s2.slot(), "second snapshot");
            HookTestSupport.check(Files.isRegularFile(root.resolve("advancements/p.json/S2.zst")),
                    "changed copy at S2");

            // 回滚到 S1：level.dat 的读取触发 JSON 物化。
            runtime.arm("S1");
            runtime.resolveFile(world.resolve("level.dat"));
            HookTestSupport.check(java.util.Arrays.equals(a1,
                    Files.readAllBytes(world.resolve("advancements/p.json"))),
                    "S1 json restored");
            runtime.disarm();

            runtime.arm("S2");
            runtime.resolveFile(world.resolve("level.dat"));
            HookTestSupport.check(java.util.Arrays.equals(a2,
                    Files.readAllBytes(world.resolve("advancements/p.json"))),
                    "S2 json restored");
            runtime.disarm();

            // 校验把 JSON 副本算入目标数，且不报错。
            BackupRuntime.VerifyReport report = runtime.verify("S2");
            HookTestSupport.check(report.targets >= 2, "verify counts json targets");
            HookTestSupport.eq(0L, report.failures, "verify has no failures");
        } finally {
            ConfigHolder.values().set("backup.copyJson", previous);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
