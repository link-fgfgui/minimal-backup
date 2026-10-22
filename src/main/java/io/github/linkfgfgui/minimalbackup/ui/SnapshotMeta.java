package io.github.linkfgfgui.minimalbackup.ui;

import io.github.linkfgfgui.minimalbackup.engine.Snapshot;
import java.util.Objects;

/** Metadata exchanged between the server service and the client UI. No NBT or block bytes. */
public record SnapshotMeta(String id, int generation, String parent, String name, String createdBy,
                           String source, long createdAt, boolean tombstone, boolean damaged,
                           int entryCount, long logicalBytes, long physicalBytes,
                           String mcVersion, String modVersion) {
    public SnapshotMeta {
        Objects.requireNonNull(id, "id");
        parent = parent == null ? "" : parent;
        name = name == null ? "" : name;
        createdBy = createdBy == null ? "" : createdBy;
        source = source == null ? "" : source;
        mcVersion = mcVersion == null ? "" : mcVersion;
        modVersion = modVersion == null ? "" : modVersion;
    }

    public static SnapshotMeta from(Snapshot snapshot) {
        return new SnapshotMeta(snapshot.slot(), snapshot.generation(), snapshot.parentSlot(),
                snapshot.displayName(), snapshot.createdBy(), snapshot.source(), snapshot.savedAt(),
                snapshot.tombstone(), snapshot.damaged(), snapshot.entryCount(),
                snapshot.logicalBytes(), snapshot.physicalBytes(), snapshot.minecraftVersion(),
                snapshot.engineVersion());
    }

    /** Rebuilds the engine value object used by the tree widget. */
    public Snapshot toSnapshot() {
        return new Snapshot.Builder(id).generation(generation).parentSlot(parent)
                .displayName(name).createdBy(createdBy).source(source).savedAt(createdAt)
                .tombstone(tombstone).damaged(damaged).entryCount(entryCount)
                .logicalBytes(logicalBytes).physicalBytes(physicalBytes)
                .minecraftVersion(mcVersion).engineVersion(modVersion).build();
    }
}
