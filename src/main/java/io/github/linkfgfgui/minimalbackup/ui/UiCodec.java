package io.github.linkfgfgui.minimalbackup.ui;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Versioned, loader-neutral codec for the service protocol. */
public final class UiCodec {
    public static final int VERSION = 1;
    public static final int MAX_SNAPSHOTS = 10_000;

    public interface Writer {
        void writeInt(int value) throws IOException;

        void writeLong(long value) throws IOException;

        void writeBoolean(boolean value) throws IOException;

        void writeString(String value) throws IOException;
    }

    public interface Reader {
        int readInt() throws IOException;

        long readLong() throws IOException;

        boolean readBoolean() throws IOException;

        String readString() throws IOException;
    }

    private UiCodec() {
    }

    public static Writer of(DataOutput out) {
        return new Writer() {
            @Override
            public void writeInt(int value) throws IOException {
                out.writeInt(value);
            }

            @Override
            public void writeLong(long value) throws IOException {
                out.writeLong(value);
            }

            @Override
            public void writeBoolean(boolean value) throws IOException {
                out.writeBoolean(value);
            }

            @Override
            public void writeString(String value) throws IOException {
                out.writeUTF(value);
            }
        };
    }

    public static Reader of(DataInput in) {
        return new Reader() {
            @Override
            public int readInt() throws IOException {
                return in.readInt();
            }

            @Override
            public long readLong() throws IOException {
                return in.readLong();
            }

            @Override
            public boolean readBoolean() throws IOException {
                return in.readBoolean();
            }

            @Override
            public String readString() throws IOException {
                return in.readUTF();
            }
        };
    }

    public static void writeRequest(Writer out, BackupRequest request) throws IOException {
        out.writeInt(VERSION);
        out.writeInt(request.action().ordinal());
        out.writeString(request.slot());
        out.writeString(request.name());
        out.writeBoolean(request.confirm());
    }

    public static BackupRequest readRequest(Reader in) throws IOException {
        checkVersion(in.readInt());
        int ordinal = in.readInt();
        BackupRequest.Action[] actions = BackupRequest.Action.values();
        if (ordinal < 0 || ordinal >= actions.length) {
            throw new IOException("bad request action: " + ordinal);
        }
        String slot = in.readString();
        String name = in.readString();
        boolean confirm = in.readBoolean();
        return new BackupRequest(actions[ordinal], slot, name, confirm);
    }

    public static void writeResponse(Writer out, BackupResponse response) throws IOException {
        out.writeInt(VERSION);
        out.writeBoolean(response.ok());
        out.writeString(response.code());
        out.writeString(response.message());
        if (response.snapshots().size() > MAX_SNAPSHOTS) {
            throw new IOException("too many snapshots: " + response.snapshots().size());
        }
        out.writeInt(response.snapshots().size());
        for (SnapshotMeta meta : response.snapshots()) {
            writeMeta(out, meta);
        }
    }

    public static BackupResponse readResponse(Reader in) throws IOException {
        checkVersion(in.readInt());
        boolean ok = in.readBoolean();
        String code = in.readString();
        String message = in.readString();
        int size = in.readInt();
        if (size < 0 || size > MAX_SNAPSHOTS) {
            throw new IOException("bad snapshot count: " + size);
        }
        List<SnapshotMeta> snapshots = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            snapshots.add(readMeta(in));
        }
        return new BackupResponse(ok, code, message, snapshots);
    }

    private static void writeMeta(Writer out, SnapshotMeta meta) throws IOException {
        out.writeString(meta.id());
        out.writeInt(meta.generation());
        out.writeString(meta.parent());
        out.writeString(meta.name());
        out.writeString(meta.createdBy());
        out.writeString(meta.source());
        out.writeLong(meta.createdAt());
        out.writeBoolean(meta.tombstone());
        out.writeBoolean(meta.damaged());
        out.writeInt(meta.entryCount());
        out.writeLong(meta.logicalBytes());
        out.writeLong(meta.physicalBytes());
        out.writeString(meta.mcVersion());
        out.writeString(meta.modVersion());
    }

    private static SnapshotMeta readMeta(Reader in) throws IOException {
        return new SnapshotMeta(in.readString(), in.readInt(), in.readString(), in.readString(),
                in.readString(), in.readString(), in.readLong(), in.readBoolean(),
                in.readBoolean(), in.readInt(), in.readLong(), in.readLong(), in.readString(),
                in.readString());
    }

    private static void checkVersion(int version) throws IOException {
        if (version != VERSION) {
            throw new IOException("UI protocol mismatch: server=" + version + " client=" + VERSION);
        }
    }
}
