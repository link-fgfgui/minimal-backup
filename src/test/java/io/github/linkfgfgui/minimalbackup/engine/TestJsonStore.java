package io.github.linkfgfgui.minimalbackup.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** E11: JSON 整文件副本、稀疏版本、删除标记、回滚物化、GC 与校验。 */
final class TestJsonStore {
    private TestJsonStore() {
    }

    static void run() throws Exception {
        Path dir = TestSupport.tempDir("jsonstore");
        try {
            capture(dir);
        } finally {
            TestSupport.deleteTree(dir);
        }
    }

    private static void capture(Path dir) throws IOException {
        Path world = dir.resolve("world");
        Path root = dir.resolve("vault/minimalbackup_snapshots");
        Files.createDirectories(world);

        byte[] a1 = TestSupport.text("{\"a\":1}");
        byte[] b1 = TestSupport.text("{\"b\":1}");
        TestSupport.write(world.resolve("stats/a.json"), a1);
        TestSupport.write(world.resolve("advancements/b.json"), b1);
        // 一级目录之外的不在范围内：根目录文件与更深层的 json。
        TestSupport.write(world.resolve("root.json"), TestSupport.text("{}"));
        TestSupport.write(world.resolve("datapacks/nested/c.json"), TestSupport.text("{}"));

        JsonStore.Plan first = JsonStore.plan(world, root, 3);
        TestSupport.eq(2, first.entries().size(), "first plan size");
        TestSupport.check(first.logicalBytes() == a1.length + b1.length, "first logical bytes");
        TestSupport.check(JsonStore.write(root, first, "S1") > 0L, "first write bytes");
        TestSupport.check(Files.isRegularFile(root.resolve("stats/a.json/S1.zst")),
                "stats copy at S1");
        TestSupport.check(Files.isRegularFile(root.resolve("advancements/b.json/S1.zst")),
                "advancements copy at S1");
        TestSupport.check(!Files.exists(root.resolve("root.json")), "root json ignored");
        TestSupport.check(!Files.exists(root.resolve("datapacks")), "nested json ignored");

        TestSupport.check(JsonStore.plan(world, root, 3).isEmpty(), "unchanged plan empty");

        byte[] a2 = TestSupport.text("{\"a\":2}");
        TestSupport.write(world.resolve("stats/a.json"), a2);
        Files.delete(world.resolve("advancements/b.json"));
        JsonStore.Plan second = JsonStore.plan(world, root, 3);
        TestSupport.eq(2, second.entries().size(), "second plan size");
        TestSupport.check(second.entries().containsKey("advancements/b.json"), "deletion planned");
        TestSupport.check(second.entries().get("advancements/b.json") == null,
                "deletion payload is null");
        JsonStore.write(root, second, "S2");
        TestSupport.check(Files.isRegularFile(root.resolve("stats/a.json/S2.zst")),
                "stats copy at S2");
        TestSupport.check(Files.isRegularFile(root.resolve("advancements/b.json/S2.absent")),
                "advancements absent at S2");

        Files.deleteIfExists(world.resolve("stats/a.json"));
        TestSupport.eq(2, JsonStore.materialize(world, root, 1), "materialized S1");
        TestSupport.bytesEq(a1, Files.readAllBytes(world.resolve("stats/a.json")),
                "stats restored to S1");
        TestSupport.bytesEq(b1, Files.readAllBytes(world.resolve("advancements/b.json")),
                "advancements restored to S1");

        TestSupport.eq(2, JsonStore.materialize(world, root, 2), "materialized S2");
        TestSupport.bytesEq(a2, Files.readAllBytes(world.resolve("stats/a.json")),
                "stats restored to S2");
        TestSupport.check(!Files.exists(world.resolve("advancements/b.json")),
                "advancements deleted at S2");

        long[] counters = new long[4];
        List<String> errors = new ArrayList<>();
        JsonStore.verify(root, 2, counters, errors, 20);
        TestSupport.eq(2L, counters[0], "verify targets");
        TestSupport.eq(1L, counters[1], "verify absent");
        TestSupport.eq(1L, counters[2], "verify present");
        TestSupport.eq(0L, counters[3], "verify failures");

        JsonStore.collectGarbage(root, 2);
        TestSupport.check(!Files.exists(root.resolve("stats/a.json/S1.zst")),
                "S1 stats copy collected");
        TestSupport.check(!Files.exists(root.resolve("advancements/b.json/S1.zst")),
                "S1 absent copy collected");

        Files.deleteIfExists(world.resolve("stats/a.json"));
        TestSupport.eq(2, JsonStore.materialize(world, root, 2), "S2 still resolvable after gc");
        TestSupport.bytesEq(a2, Files.readAllBytes(world.resolve("stats/a.json")),
                "stats still correct after gc");

        TestSupport.write(root.resolve("stats/a.json/S2.zst"), TestSupport.text("not zstd"));
        long[] broken = new long[4];
        List<String> brokenErrors = new ArrayList<>();
        JsonStore.verify(root, 2, broken, brokenErrors, 20);
        TestSupport.eq(1L, broken[3], "corrupt copy counted");
        TestSupport.check(!brokenErrors.isEmpty(), "corrupt copy has an error");
    }
}
