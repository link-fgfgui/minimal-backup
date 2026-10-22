package io.github.linkfgfgui.minimalbackup.server;

import io.github.linkfgfgui.minimalbackup.engine.anvil.AnvilRegion;
import java.nio.file.Path;

/** Converts game paths into world-relative snapshot keys. */
public final class SnapshotKeys {
    private SnapshotKeys() {
    }

    /**
     * Chunk key: the folder is the actual region/entities/poi folder on disk, so the dimension
     * layout is derived by relativising it against the world directory instead of guessing.
     *
     * @return normalized relative path, or {@code null} when the folder is outside the world
     */
    public static String chunkKey(Path worldDir, Path folder, int regionX, int regionZ) {
        String rel = relative(worldDir, folder);
        if (rel == null) {
            return null;
        }
        String file = "r." + regionX + "." + regionZ + ".mca";
        return rel.isEmpty() ? file : rel + "/" + file;
    }

    /** File key (SavedData, playerdata, level.dat). */
    public static String fileKey(Path worldDir, Path file) {
        return relative(worldDir, file);
    }

    /** Chunk sub-target: ChunkPos packed as long. */
    public static long chunkSubTarget(int x, int z) {
        return AnvilRegion.chunkSubTarget(x, z);
    }

    private static String relative(Path root, Path path) {
        if (root == null || path == null) {
            return null;
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedPath = path.toAbsolutePath().normalize();
        if (!normalizedPath.startsWith(normalizedRoot)) {
            return null;
        }
        String rel = normalizedRoot.relativize(normalizedPath).toString().replace('\\', '/');
        return rel.isEmpty() ? "" : rel;
    }
}
