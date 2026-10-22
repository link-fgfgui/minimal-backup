package io.github.linkfgfgui.minimalbackup.server;

/**
 * True while a restore is replacing the server instance. The shared {@code UtilMixin} reads it to
 * keep {@code Util.shutdownExecutors()} from killing the executors the new instance needs.
 */
public final class RestoreSwap {
    public static volatile boolean swapping;

    private RestoreSwap() {
    }
}
