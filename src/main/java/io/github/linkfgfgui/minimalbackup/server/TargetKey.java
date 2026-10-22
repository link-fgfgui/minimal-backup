package io.github.linkfgfgui.minimalbackup.server;

/** A logical target: world-relative path plus sub-target (0 = whole file). */
public record TargetKey(String relPath, long subTarget) {
    public TargetKey {
        if (relPath == null) {
            throw new IllegalArgumentException("null relPath");
        }
    }
}
