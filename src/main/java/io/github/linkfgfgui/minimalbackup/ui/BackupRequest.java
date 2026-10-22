package io.github.linkfgfgui.minimalbackup.ui;

/** One write/read operation requested by the UI or the command layer. */
public record BackupRequest(Action action, String slot, String name, boolean confirm) {
    public enum Action {
        LIST, CREATE, RESTORE, DELETE, RENAME, VERIFY, GC, BASELINE, STATUS, DISARM
    }

    public BackupRequest {
        if (action == null) {
            throw new IllegalArgumentException("null action");
        }
        slot = slot == null ? "" : slot;
        name = name == null ? "" : name;
        rejectControl(slot);
        rejectControl(name);
    }

    public static BackupRequest of(Action action) {
        return new BackupRequest(action, "", "", false);
    }

    private static void rejectControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n' || c == '\r' || c == '\0') {
                throw new IllegalArgumentException("control character in request");
            }
        }
    }
}
