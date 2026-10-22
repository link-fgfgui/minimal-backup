package io.github.linkfgfgui.minimalbackup.server;

import io.github.linkfgfgui.minimalbackup.ui.BackupRequest;
import io.github.linkfgfgui.minimalbackup.ui.BackupResponse;
import java.util.function.Consumer;

/** Bridge between the server command, the UI packets and the optional client screen. */
public final class UiBridge {
    public static volatile Runnable opener;
    /** Client entry: sends a request over the loader channel. */
    public static volatile Consumer<BackupRequest> requestSender;
    /** Client entry: receives a response and shows it on the open screen. */
    public static volatile Consumer<BackupResponse> responseConsumer;
    /** Client entry: the server armed a restore and is holding the connection. */
    public static volatile Consumer<String> restoreSignal;
    /** Client entry: the replacement server is up and the client should reconnect. */
    public static volatile Consumer<String> restoreReady;

    private UiBridge() {
    }

    public static boolean available() {
        return opener != null;
    }

    public static void open() {
        Runnable current = opener;
        if (current != null) {
            current.run();
        }
    }

    public static void sendRequest(BackupRequest request) {
        Consumer<BackupRequest> current = requestSender;
        if (current != null) {
            current.accept(request);
        }
    }

    public static void acceptRestoreSignal(String slot) {
        Consumer<String> current = restoreSignal;
        if (current != null) {
            current.accept(slot);
        }
    }

    public static void acceptRestoreReady(String slot) {
        Consumer<String> current = restoreReady;
        if (current != null) {
            current.accept(slot);
        }
    }

    public static void acceptResponse(BackupResponse response) {
        Consumer<BackupResponse> current = responseConsumer;
        if (current != null) {
            current.accept(response);
        }
    }
}
