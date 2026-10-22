package io.github.linkfgfgui.minimalbackup.server;

import io.github.linkfgfgui.minimalbackup.engine.Checkpoint;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import net.minecraft.nbt.CompoundTag;

/** Shared fixtures for the hook-side tests, which run against a real Minecraft NBT classpath. */
final class HookTestSupport {
    private HookTestSupport() {
    }

    static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    static void eq(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
        }
    }

    static void eq(long expected, long actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
        }
    }

    static void contains(String haystack, String needle, String message) {
        if (haystack == null || !haystack.contains(needle)) {
            throw new AssertionError(message + ": expected to contain '" + needle + "', got " + haystack);
        }
    }

    static void expectIOException(IoAction action, String needle, String message) {
        try {
            action.run();
        } catch (Exception e) {
            contains(e.getMessage(), needle, message);
            return;
        }
        throw new AssertionError(message + ": expected an exception");
    }

    @FunctionalInterface
    interface IoAction {
        void run() throws Exception;
    }

    static CompoundTag tag(String marker, int value) {
        CompoundTag tag = new CompoundTag();
        tag.putString("marker", marker);
        tag.putInt("value", value);
        CompoundTag nested = new CompoundTag();
        nested.putLong("stamp", 123456789L);
        tag.put("nested", nested);
        return tag;
    }

    static String marker(CompoundTag tag) {
        return tag.getString("marker");
    }

    static String text(byte[] data) throws Exception {
        return marker(NbtCodec.fromBytes(data));
    }

    static List<Checkpoint.ChangeSet.Entry> entries(BackupRuntime runtime) {
        return runtime.buffer().toChangeSet().entries();
    }

    static Path world(Path gameDir, String name) throws Exception {
        Path world = gameDir.resolve("saves").resolve(name);
        java.nio.file.Files.createDirectories(world.resolve("region"));
        java.nio.file.Files.createDirectories(world.resolve("data"));
        java.nio.file.Files.createDirectories(world.resolve("playerdata"));
        return world;
    }

    static String utf8(String value) {
        return new String(value.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }
}
