package io.github.linkfgfgui.minimalbackup.server;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/**
 * NBT file reader for the optional full baseline importer.
 *
 * <p>1.21.1 exposes {@code readCompressed(Path, NbtAccounter)}; 1.20.1 exposes
 * {@code readCompressed(File)}. Reflection keeps one source file for both without an Anvil parser.
 */
public final class PlatformNbt {
    private PlatformNbt() {
    }

    public static CompoundTag read(Path path) throws IOException {
        try {
            Method method = NbtIo.class.getMethod("readCompressed", Path.class,
                    NbtAccounter.class);
            Method accounter = NbtAccounter.class.getMethod("unlimitedHeap");
            return (CompoundTag) method.invoke(null, path, accounter.invoke(null));
        } catch (NoSuchMethodException ignored) {
            // 1.20.1 fallback below.
        } catch (ReflectiveOperationException e) {
            throw unwrap(e);
        }
        try {
            Method method = NbtIo.class.getMethod("readCompressed", File.class);
            return (CompoundTag) method.invoke(null, path.toFile());
        } catch (NoSuchMethodException ignored) {
            // Stream fallback below.
        } catch (ReflectiveOperationException e) {
            throw unwrap(e);
        }
        try (InputStream in = Files.newInputStream(path)) {
            Method method = NbtIo.class.getMethod("readCompressed", InputStream.class);
            return (CompoundTag) method.invoke(null, in);
        } catch (ReflectiveOperationException e) {
            throw unwrap(e);
        }
    }

    private static IOException unwrap(ReflectiveOperationException e) {
        Throwable cause = e instanceof InvocationTargetException invocation
                ? invocation.getCause() : e;
        return cause instanceof IOException io ? io : new IOException(cause);
    }
}
