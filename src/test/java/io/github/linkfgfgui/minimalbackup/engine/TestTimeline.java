package io.github.linkfgfgui.minimalbackup.engine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

/** E5: Snapshot/Timeline 往返、缺字段回退、原子替换、gen 递增、非法快照名。 */
final class TestTimeline {
    private TestTimeline() {
    }

    static void run() throws Exception {
        Path dir = TestSupport.tempDir("timeline");
        try {
            Path index = dir.resolve("index.properties");
            Timeline timeline = Timeline.empty();
            timeline.add(snapshot("S0", 1, "", 1000L, "第一个"));
            timeline.add(snapshot("S1", 2, "S0", 2000L, "second"));
            timeline.add(snapshot("S2", 3, "S1", 3000L, "third"));
            timeline.add(snapshot("S3", 4, "S2", 4000L, "fourth"));
            timeline.setHead("S3");
            timeline.putCacheOffset("level.dat", 1024L);
            timeline.putCacheOffset("region/r.0.0.mca", 2048L);
            timeline.save(index);

            Timeline loaded = Timeline.load(index);
            TestSupport.eq(4, loaded.size(), "snapshot count");
            TestSupport.eq("S3", loaded.head(), "head");
            TestSupport.eq(5, loaded.peekGen(), "next gen");
            TestSupport.eq(1024L, loaded.cacheOffset("level.dat").longValue(), "cache level.dat");
            TestSupport.eq(2048L, loaded.cacheOffset("region/r.0.0.mca").longValue(), "cache region");
            Snapshot s0 = loaded.snapshot("S0");
            TestSupport.eq("S0", s0.slot(), "slot");
            TestSupport.eq(1, s0.generation(), "generation");
            TestSupport.eq("", s0.parentSlot(), "root parent");
            TestSupport.eq(1000L, s0.savedAt(), "savedAt");
            TestSupport.check(!s0.damaged(), "not damaged");
            TestSupport.check(!s0.tombstone(), "not tombstone");
            TestSupport.eq("第一个", s0.displayName(), "utf8 display name");
            TestSupport.eq("OurWorld", s0.worldName(), "world name");
            TestSupport.eq("1.21.1", s0.minecraftVersion(), "mc version");
            TestSupport.eq("0.1", s0.engineVersion(), "engine version");
            TestSupport.eq("manual", s0.source(), "source");
            TestSupport.eq(812, s0.entryCount(), "entry count");
            TestSupport.eq(12345678L, s0.logicalBytes(), "logical bytes");
            TestSupport.eq(345678L, s0.physicalBytes(), "physical bytes");
            TestSupport.eq("S0", loaded.snapshot("S1").parentSlot(), "parent link");

            TestSupport.eq(setOf(1, 2, 3), loaded.visibleGenerations("S2"), "visible S2");
            TestSupport.eq(setOf(1), loaded.visibleGenerations("S0"), "visible S0");
            TestSupport.eq(setOf(1, 2, 3, 4), loaded.liveGenerations(), "live head chain");

            loaded.rename("S2", "renamed");
            TestSupport.eq("renamed", loaded.snapshot("S2").displayName(), "rename sets the name");
            TestSupport.eq(3, loaded.snapshot("S2").generation(), "rename keeps generation");
            TestSupport.eq("S1", loaded.snapshot("S2").parentSlot(), "rename keeps parent");
            loaded.tombstone("S2");
            TestSupport.check(loaded.snapshot("S2").tombstone(), "tombstone flag");
            TestSupport.eq(3, loaded.snapshot("S2").generation(), "tombstone keeps generation");
            TestSupport.eq(setOf(1, 2, 3, 4), loaded.liveGenerations(), "tombstone keeps the chain");

            TestSupport.eq(5, loaded.allocateGen(), "allocate gen");
            TestSupport.eq(6, loaded.peekGen(), "gen advanced");
            loaded.save(index);
            TestSupport.eq(6, Timeline.load(index).peekGen(), "gen persisted");

            try (Stream<Path> files = Files.list(dir)) {
                TestSupport.check(files.noneMatch(path -> path.getFileName().toString().endsWith(AtomicFiles.TEMP_SUFFIX)),
                        "no temp file left");
            }

            String savedIndex = new String(Files.readAllBytes(index), StandardCharsets.UTF_8);
            TestSupport.check(savedIndex.contains("codec=" + Codec.id() + "\n"),
                    "codec stamped on save");
            TestSupport.check(savedIndex.contains("canon=" + Timeline.CANON + "\n"),
                    "canon stamped on save");

            Path minimal = dir.resolve("minimal.properties");
            Files.write(minimal, ("format=1\n"
                    + "slot.S0.gen=1\n"
                    + "slot.S0.parentSlot=\n").getBytes(StandardCharsets.UTF_8));
            Timeline fallback = Timeline.load(minimal);
            TestSupport.eq(1, fallback.size(), "minimal count");
            TestSupport.eq(2, fallback.peekGen(), "minimal gen");
            TestSupport.eq("", fallback.snapshot("S0").displayName(), "missing display name");
            TestSupport.eq(0L, fallback.snapshot("S0").savedAt(), "missing savedAt");
            TestSupport.eq(0, fallback.snapshot("S0").entryCount(), "missing entryCount");

            Path badFormat = dir.resolve("bad-format.properties");
            Files.write(badFormat, "format=99\n".getBytes(StandardCharsets.UTF_8));
            TestSupport.expectIOException(() -> Timeline.load(badFormat), "unsupported format");

            Path foreignCanon = dir.resolve("foreign-canon.properties");
            Files.write(foreignCanon, ("format=1\ncanon=nbt2\n").getBytes(StandardCharsets.UTF_8));
            TestSupport.expectIOException(() -> Timeline.load(foreignCanon), "unknown canon scheme");

            Path noCanon = dir.resolve("no-canon.properties");
            Files.write(noCanon, ("format=1\nslot.S0.gen=1\nslot.S0.parentSlot=\n")
                    .getBytes(StandardCharsets.UTF_8));
            TestSupport.eq(1, Timeline.load(noCanon).size(), "pre-canon vault still loads");

            Path foreignCodec = dir.resolve("foreign-codec.properties");
            Files.write(foreignCodec, ("format=1\ncodec=hdiff-lzma2/1\n").getBytes(StandardCharsets.UTF_8));
            TestSupport.expectIOException(() -> Timeline.load(foreignCodec), "foreign codec vault");

            // Codec addons are mutually exclusive: a customAST vault must be rejected by a zstd/1
            // runtime (and vice versa) before any block is touched.
            Path customAst = dir.resolve("customast.properties");
            Files.write(customAst, ("format=1\ncodec=customast-zstd/1\n").getBytes(StandardCharsets.UTF_8));
            TestSupport.expectIOException(() -> Timeline.load(customAst), "customAST vault under zstd/1");

            // The two archived customAST addons differ only in codecId; each must reject the
            // other's vault before any block is touched.
            Path customLzma = dir.resolve("customast-lzma.properties");
            Files.write(customLzma, ("format=1\ncodec=customast-lzma/1\n").getBytes(StandardCharsets.UTF_8));
            TestSupport.expectIOException(() -> Timeline.load(customLzma), "customast-lzma vault under zstd/1");

            Path badUnicode = dir.resolve("bad-unicode.properties");
            Files.write(badUnicode, ("format=1\nslot.S0.gen=" + (char) 92 + "uZZZZ\n")
                    .getBytes(StandardCharsets.UTF_8));
            TestSupport.expectIOException(() -> Timeline.load(badUnicode), "malformed unicode");

            Path badHead = dir.resolve("bad-head.properties");
            Files.write(badHead, ("format=1\nhead=S9\nslot.S0.gen=1\nslot.S0.parentSlot=\n")
                    .getBytes(StandardCharsets.UTF_8));
            TestSupport.expectIOException(() -> Timeline.load(badHead), "missing head");

            Timeline names = Timeline.empty();
            TestSupport.expectIllegalArgument(() -> names.add(new Snapshot.Builder("a.b").generation(1).build()),
                    "dot in name");
            names.add(new Snapshot.Builder("S0").generation(1).build());
            TestSupport.expectIllegalArgument(() -> names.add(new Snapshot.Builder("S0").generation(2).build()),
                    "duplicate name");

            Timeline cycle = Timeline.empty();
            cycle.add(snapshot("A", 1, "B", 1L, ""));
            cycle.add(snapshot("B", 2, "A", 2L, ""));
            TestSupport.eq(2, cycle.visibleGenerations("A").size(), "cycle bounded");
        } finally {
            TestSupport.deleteTree(dir);
        }
    }

    private static Snapshot snapshot(String slot, int generation, String parent, long savedAt, String name) {
        return new Snapshot.Builder(slot)
                .generation(generation)
                .parentSlot(parent)
                .savedAt(savedAt)
                .damaged(false)
                .tombstone(false)
                .displayName(name)
                .worldName("OurWorld")
                .minecraftVersion("1.21.1")
                .engineVersion("0.1")
                .source("manual")
                .entryCount(812)
                .logicalBytes(12345678L)
                .physicalBytes(345678L)
                .build();
    }

    private static Set<Integer> setOf(Integer... values) {
        return new HashSet<>(Arrays.asList(values));
    }
}
