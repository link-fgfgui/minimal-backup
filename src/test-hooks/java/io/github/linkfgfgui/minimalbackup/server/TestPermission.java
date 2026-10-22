package io.github.linkfgfgui.minimalbackup.server;

import io.github.linkfgfgui.minimalbackup.ui.BackupRequest;

/** H9: the read-only action set must match the policy used by the UI and the packet gate. */
final class TestPermission {
    private TestPermission() {
    }

    static void run() {
        for (BackupRequest.Action action : BackupRequest.Action.values()) {
            boolean expected = action == BackupRequest.Action.LIST
                    || action == BackupRequest.Action.STATUS;
            HookTestSupport.check(BackupService.isReadOnly(action) == expected,
                    "unexpected read-only classification for " + action);
        }
        HookTestSupport.check(BackupService.REQUIRED_PERMISSION == 2,
                "required permission level changed");
        HookTestSupport.contains(BackupService.PERMISSION_MESSAGE, "2",
                "permission message must name the level");
    }
}
