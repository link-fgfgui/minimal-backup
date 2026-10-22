package io.github.linkfgfgui.minimalbackup.ui;

import java.util.List;

/** Result returned to both the command layer (chat lines) and the UI (status/table). */
public record BackupResponse(boolean ok, String code, String message,
                             List<SnapshotMeta> snapshots) {
    public BackupResponse {
        code = code == null ? "" : code;
        message = message == null ? "" : message;
        snapshots = snapshots == null ? List.of() : List.copyOf(snapshots);
    }

    public static BackupResponse ok(String message) {
        return new BackupResponse(true, "ok", message, List.of());
    }

    public static BackupResponse ok(String message, List<SnapshotMeta> snapshots) {
        return new BackupResponse(true, "ok", message, snapshots);
    }

    public static BackupResponse fail(String code, String message) {
        return new BackupResponse(false, code, message, List.of());
    }
}
