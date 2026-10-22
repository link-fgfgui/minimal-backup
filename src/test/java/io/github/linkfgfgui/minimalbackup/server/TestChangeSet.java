package io.github.linkfgfgui.minimalbackup.server;

import io.github.linkfgfgui.minimalbackup.engine.Block;
import io.github.linkfgfgui.minimalbackup.engine.Checkpoint;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ChangeSetTest: overwrite, tombstone, limits and the baseline+latest pair.
 */
public final class TestChangeSet {
    private TestChangeSet() {
    }

    public static void run() {
        overwriteKeepsLatestOnly();
        absentBecomesTombstone();
        baselineAndLatestProduceTwoEntries();
        limitFiresOverflowOnce();
        clearResetsEverything();
    }

    private static void overwriteKeepsLatestOnly() {
        ChangeSetBuffer buffer = new ChangeSetBuffer(Integer.MAX_VALUE, Long.MAX_VALUE);
        TargetKey key = new TargetKey("region/r.0.0.mca", 1L);
        buffer.setLatest(key, Block.TYPE_CHUNK, bytes("first"));
        buffer.setLatest(key, Block.TYPE_CHUNK, bytes("second"));
        List<Checkpoint.ChangeSet.Entry> entries = buffer.toChangeSet().entries();
        TestServerSupport.eq(1, entries.size(), "one entry per target");
        TestServerSupport.eq("second", text(entries.get(0).data()), "latest value wins");
        TestServerSupport.eq((long) "second".length(), buffer.bytes(), "byte accounting follows latest");
    }

    private static void absentBecomesTombstone() {
        ChangeSetBuffer buffer = new ChangeSetBuffer(Integer.MAX_VALUE, Long.MAX_VALUE);
        TargetKey key = new TargetKey("data/raids.dat", 0L);
        buffer.setLatest(key, Block.TYPE_FILE, bytes("x"));
        buffer.setLatestAbsent(key, Block.TYPE_FILE);
        List<Checkpoint.ChangeSet.Entry> entries = buffer.toChangeSet().entries();
        TestServerSupport.eq(1, entries.size(), "tombstone replaces value");
        TestServerSupport.check(entries.get(0).absent(), "entry is a tombstone");
        TestServerSupport.eq(0L, buffer.bytes(), "tombstone frees the bytes");
    }

    private static void baselineAndLatestProduceTwoEntries() {
        ChangeSetBuffer buffer = new ChangeSetBuffer(Integer.MAX_VALUE, Long.MAX_VALUE);
        TargetKey key = new TargetKey("region/r.0.0.mca", 2L);
        buffer.setBaseline(key, Block.TYPE_CHUNK, bytes("old"));
        buffer.setBaseline(key, Block.TYPE_CHUNK, bytes("ignored"));
        buffer.setLatest(key, Block.TYPE_CHUNK, bytes("new"));
        List<Checkpoint.ChangeSet.Entry> entries = buffer.toChangeSet().entries();
        TestServerSupport.eq(2, entries.size(), "baseline plus latest");
        TestServerSupport.eq("old", text(entries.get(0).data()), "first entry is the baseline");
        TestServerSupport.eq("new", text(entries.get(1).data()), "second entry is the latest");
        TestServerSupport.eq((long) ("old".length() + "new".length()), buffer.bytes(),
                "both values counted");
    }

    private static void limitFiresOverflowOnce() {
        ChangeSetBuffer buffer = new ChangeSetBuffer(1, 1024 * 1024);
        AtomicInteger fired = new AtomicInteger();
        buffer.setOverflowHandler(fired::incrementAndGet);
        buffer.setLatest(new TargetKey("a", 0L), Block.TYPE_FILE, bytes("a"));
        buffer.setLatest(new TargetKey("b", 0L), Block.TYPE_FILE, bytes("b"));
        TestServerSupport.eq(1, fired.get(), "overflow fires once while above the limit");
        buffer.clear();
        buffer.setLatest(new TargetKey("c", 0L), Block.TYPE_FILE, bytes("c"));
        TestServerSupport.eq(1, fired.get(), "a later single key stays under the limit");
    }

    private static void clearResetsEverything() {
        ChangeSetBuffer buffer = new ChangeSetBuffer(Integer.MAX_VALUE, Long.MAX_VALUE);
        TargetKey key = new TargetKey("level.dat", 0L);
        buffer.setBaseline(key, Block.TYPE_FILE, bytes("old"));
        buffer.setLatest(key, Block.TYPE_FILE, bytes("new"));
        TestServerSupport.check(!buffer.isEmpty(), "not empty before clear");
        buffer.clear();
        TestServerSupport.check(buffer.isEmpty(), "empty after clear");
        TestServerSupport.eq(0L, buffer.bytes(), "bytes reset");
        TestServerSupport.check(!buffer.hasBaseline(key), "baseline cleared");
        TestServerSupport.check(!buffer.hasLatest(key), "latest cleared");
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }
}
