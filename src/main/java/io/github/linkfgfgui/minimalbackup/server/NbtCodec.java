package io.github.linkfgfgui.minimalbackup.server;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

/** NBT byte conversion shared by every loader node. */
public final class NbtCodec {
    private NbtCodec() {
    }

    /** Uncompressed NBT bytes, matching the block payload format. */
    public static byte[] toBytes(CompoundTag tag) {
        if (tag == null) {
            throw new IllegalArgumentException("null tag");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(512);
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                NbtIo.write(tag, out);
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("cannot serialise NBT", e);
        }
    }

    public static CompoundTag fromBytes(byte[] data) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            return NbtIo.read(in);
        }
    }

    /** Reads a .dat file that may be gzip-compressed (vanilla writes gzip). */
    public static CompoundTag readPossiblyCompressed(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try (InputStream raw = Files.newInputStream(file);
             PushbackInputStream pushback = new PushbackInputStream(raw, 2)) {
            int first = pushback.read();
            int second = pushback.read();
            if (second >= 0) {
                pushback.unread(second);
            }
            if (first >= 0) {
                pushback.unread(first);
            }
            InputStream input = first == 0x1F && second == 0x8B
                    ? new GZIPInputStream(pushback)
                    : pushback;
            try (DataInputStream in = new DataInputStream(input)) {
                return NbtIo.read(in);
            }
        }
    }
}
