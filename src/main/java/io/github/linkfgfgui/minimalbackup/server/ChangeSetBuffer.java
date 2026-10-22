package io.github.linkfgfgui.minimalbackup.server;

import io.github.linkfgfgui.minimalbackup.engine.Block;
import io.github.linkfgfgui.minimalbackup.engine.Checkpoint;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory change set: per target at most a baseline (value before the first tracked write)
 * and the latest value. Nothing is written to disk until a checkpoint.
 */
public final class ChangeSetBuffer {
    private final Map<TargetKey, Slot> slots = new LinkedHashMap<>();
    private int maxKeys;
    private long maxBytes;
    private long bytes;
    private Runnable overflowHandler;
    private boolean firingOverflow;

    public ChangeSetBuffer(int maxKeys, long maxBytes) {
        configure(maxKeys, maxBytes);
    }

    public void configure(int maxKeys, long maxBytes) {
        this.maxKeys = Math.max(1, maxKeys);
        this.maxBytes = Math.max(1L, maxBytes);
    }

    public void setOverflowHandler(Runnable handler) {
        this.overflowHandler = handler;
    }

    public synchronized boolean isEmpty() {
        return slots.isEmpty();
    }

    public synchronized int size() {
        return slots.size();
    }

    public synchronized long bytes() {
        return bytes;
    }

    public synchronized boolean hasBaseline(TargetKey key) {
        Slot slot = slots.get(key);
        return slot != null && slot.baselineSet;
    }

    public synchronized boolean hasLatest(TargetKey key) {
        Slot slot = slots.get(key);
        return slot != null && slot.latestSet;
    }

    /** First value wins; later read-capture calls do not overwrite the baseline. */
    public synchronized void setBaseline(TargetKey key, int type, byte[] data) {
        Slot slot = slots.computeIfAbsent(key, ignored -> new Slot(type));
        if (slot.baselineSet) {
            return;
        }
        slot.baselineSet = true;
        slot.baselineAbsent = false;
        slot.baseline = data.clone();
        bytes += data.length;
        checkLimits();
    }

    public synchronized void setBaselineAbsent(TargetKey key, int type) {
        Slot slot = slots.computeIfAbsent(key, ignored -> new Slot(type));
        if (slot.baselineSet) {
            return;
        }
        slot.baselineSet = true;
        slot.baselineAbsent = true;
        slot.baseline = null;
        checkLimits();
    }

    public synchronized void setLatest(TargetKey key, int type, byte[] data) {
        Slot slot = slots.computeIfAbsent(key, ignored -> new Slot(type));
        if (slot.latestSet && slot.latest != null) {
            bytes -= slot.latest.length;
        }
        slot.latestSet = true;
        slot.latestAbsent = false;
        slot.latest = data.clone();
        bytes += data.length;
        checkLimits();
    }

    public synchronized void setLatestAbsent(TargetKey key, int type) {
        Slot slot = slots.computeIfAbsent(key, ignored -> new Slot(type));
        if (slot.latestSet && slot.latest != null) {
            bytes -= slot.latest.length;
        }
        slot.latestSet = true;
        slot.latestAbsent = true;
        slot.latest = null;
        checkLimits();
    }

    public synchronized Checkpoint.ChangeSet toChangeSet() {
        Checkpoint.ChangeSet changes = new Checkpoint.ChangeSet();
        for (Map.Entry<TargetKey, Slot> entry : slots.entrySet()) {
            TargetKey key = entry.getKey();
            Slot slot = entry.getValue();
            if (slot.baselineSet) {
                if (slot.baselineAbsent) {
                    changes.absent(key.relPath(), key.subTarget(), slot.type);
                } else {
                    changes.put(key.relPath(), key.subTarget(), slot.type, slot.baseline);
                }
            }
            if (slot.latestSet) {
                if (slot.latestAbsent) {
                    changes.absent(key.relPath(), key.subTarget(), slot.type);
                } else {
                    changes.put(key.relPath(), key.subTarget(), slot.type, slot.latest);
                }
            }
        }
        return changes;
    }

    public synchronized void clear() {
        slots.clear();
        bytes = 0L;
    }

    private void checkLimits() {
        if (firingOverflow || overflowHandler == null) {
            return;
        }
        if (slots.size() <= maxKeys && bytes <= maxBytes) {
            return;
        }
        firingOverflow = true;
        try {
            overflowHandler.run();
        } finally {
            firingOverflow = false;
        }
    }

    private static final class Slot {
        private final int type;
        private boolean baselineSet;
        private boolean baselineAbsent;
        private byte[] baseline;
        private boolean latestSet;
        private boolean latestAbsent;
        private byte[] latest;

        private Slot(int type) {
            this.type = type == Block.TYPE_CHUNK ? Block.TYPE_CHUNK : Block.TYPE_FILE;
        }
    }
}
