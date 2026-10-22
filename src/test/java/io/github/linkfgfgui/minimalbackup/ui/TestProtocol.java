package io.github.linkfgfgui.minimalbackup.ui;

import io.github.linkfgfgui.minimalbackup.engine.Snapshot;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.List;

/** Protocol round-trip for the command/UI service interface. */
public final class TestProtocol {
    private TestProtocol() {
    }

    public static void run() throws Exception {
        Snapshot snapshot = new Snapshot.Builder("S2").generation(2).parentSlot("S1")
                .displayName("名字").createdBy("00000000-0000-0000-0000-000000000001|Dev")
                .source("manual").savedAt(42L).entryCount(3).logicalBytes(100L)
                .physicalBytes(20L).minecraftVersion("1.21.1").engineVersion("0.1.0").build();
        SnapshotMeta meta = SnapshotMeta.from(snapshot);
        check(meta.toSnapshot().createdBy().equals(snapshot.createdBy()), "meta round-trip");

        BackupRequest request = new BackupRequest(BackupRequest.Action.RENAME, "S2", "new", false);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            UiCodec.writeRequest(UiCodec.of(out), request);
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            check(request.equals(UiCodec.readRequest(UiCodec.of(in))), "request round-trip");
        }

        BackupResponse response = BackupResponse.ok("2 snapshots", List.of(meta, meta));
        bytes.reset();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            UiCodec.writeResponse(UiCodec.of(out), response);
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            BackupResponse back = UiCodec.readResponse(UiCodec.of(in));
            check(back.ok() && back.snapshots().size() == 2, "response round-trip");
            check(back.snapshots().get(0).name().equals("名字"), "unicode name");
        }

        boolean rejected = false;
        try {
            new BackupRequest(BackupRequest.Action.CREATE, "", "bad\nname", false);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        check(rejected, "control characters rejected");
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }
}
