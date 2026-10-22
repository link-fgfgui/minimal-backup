package io.github.linkfgfgui.minimalbackup.engine;

/**
 * 快照节点值对象（15 个字段）：slot、gen、parentSlot、savedAt、damaged、tombstone、
 * displayName、worldName、minecraftVersion、engineVersion、source、createdBy、entryCount、
 * logicalBytes、physicalBytes。
 *
 * <p>不可变；用 {@link Builder} 构造。
 */
public final class Snapshot {
    private final String slot;
    private final int generation;
    private final String parentSlot;
    private final long savedAt;
    private final boolean damaged;
    private final boolean tombstone;
    private final String displayName;
    private final String worldName;
    private final String minecraftVersion;
    private final String engineVersion;
    private final String source;
    private final String createdBy;
    private final int entryCount;
    private final long logicalBytes;
    private final long physicalBytes;

    private Snapshot(Builder builder) {
        this.slot = builder.slot;
        this.generation = builder.generation;
        this.parentSlot = builder.parentSlot == null ? "" : builder.parentSlot;
        this.savedAt = builder.savedAt;
        this.damaged = builder.damaged;
        this.tombstone = builder.tombstone;
        this.displayName = builder.displayName == null ? "" : builder.displayName;
        this.worldName = builder.worldName == null ? "" : builder.worldName;
        this.minecraftVersion = builder.minecraftVersion == null ? "" : builder.minecraftVersion;
        this.engineVersion = builder.engineVersion == null ? "" : builder.engineVersion;
        this.source = builder.source == null ? "" : builder.source;
        this.createdBy = builder.createdBy == null ? "" : builder.createdBy;
        this.entryCount = builder.entryCount;
        this.logicalBytes = builder.logicalBytes;
        this.physicalBytes = builder.physicalBytes;
    }

    public String slot() {
        return slot;
    }

    public int generation() {
        return generation;
    }

    public String parentSlot() {
        return parentSlot;
    }

    public long savedAt() {
        return savedAt;
    }

    public boolean damaged() {
        return damaged;
    }

    public boolean tombstone() {
        return tombstone;
    }

    public String displayName() {
        return displayName;
    }

    public String worldName() {
        return worldName;
    }

    public String minecraftVersion() {
        return minecraftVersion;
    }

    public String engineVersion() {
        return engineVersion;
    }

    public String source() {
        return source;
    }

    /** Initiating player as {@code uuid|name}; empty for console/automatic snapshots. */
    public String createdBy() {
        return createdBy;
    }

    public int entryCount() {
        return entryCount;
    }

    public long logicalBytes() {
        return logicalBytes;
    }

    public long physicalBytes() {
        return physicalBytes;
    }

    /** Same node with a new display name; the slot and generation are unchanged. */
    public Snapshot withDisplayName(String name) {
        return toBuilder().displayName(name).build();
    }

    /** Same node marked as deleted; the generation stays so children keep a valid chain. */
    public Snapshot asTombstone() {
        return toBuilder().tombstone(true).build();
    }

    private Builder toBuilder() {
        return new Builder(slot)
                .generation(generation)
                .parentSlot(parentSlot)
                .savedAt(savedAt)
                .damaged(damaged)
                .tombstone(tombstone)
                .displayName(displayName)
                .worldName(worldName)
                .minecraftVersion(minecraftVersion)
                .engineVersion(engineVersion)
                .source(source)
                .createdBy(createdBy)
                .entryCount(entryCount)
                .logicalBytes(logicalBytes)
                .physicalBytes(physicalBytes);
    }

    /** 快照构造器；缺省字段留空/0。 */
    public static final class Builder {
        private final String slot;
        private int generation;
        private String parentSlot = "";
        private long savedAt;
        private boolean damaged;
        private boolean tombstone;
        private String displayName = "";
        private String worldName = "";
        private String minecraftVersion = "";
        private String engineVersion = "";
        private String source = "";
        private String createdBy = "";
        private int entryCount;
        private long logicalBytes;
        private long physicalBytes;

        public Builder(String slot) {
            this.slot = slot;
        }

        public Builder generation(int value) {
            this.generation = value;
            return this;
        }

        public Builder parentSlot(String value) {
            this.parentSlot = value;
            return this;
        }

        public Builder savedAt(long value) {
            this.savedAt = value;
            return this;
        }

        public Builder damaged(boolean value) {
            this.damaged = value;
            return this;
        }

        public Builder tombstone(boolean value) {
            this.tombstone = value;
            return this;
        }

        public Builder displayName(String value) {
            this.displayName = value;
            return this;
        }

        public Builder worldName(String value) {
            this.worldName = value;
            return this;
        }

        public Builder minecraftVersion(String value) {
            this.minecraftVersion = value;
            return this;
        }

        public Builder engineVersion(String value) {
            this.engineVersion = value;
            return this;
        }

        public Builder source(String value) {
            this.source = value;
            return this;
        }

        public Builder createdBy(String value) {
            this.createdBy = value;
            return this;
        }

        public Builder entryCount(int value) {
            this.entryCount = value;
            return this;
        }

        public Builder logicalBytes(long value) {
            this.logicalBytes = value;
            return this;
        }

        public Builder physicalBytes(long value) {
            this.physicalBytes = value;
            return this;
        }

        public Snapshot build() {
            return new Snapshot(this);
        }
    }
}
