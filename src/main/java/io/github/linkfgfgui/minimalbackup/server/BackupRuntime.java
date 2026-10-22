package io.github.linkfgfgui.minimalbackup.server;

import io.github.linkfgfgui.minimalbackup.MinimalBackup;
import io.github.linkfgfgui.minimalbackup.config.ConfigHolder;
import io.github.linkfgfgui.minimalbackup.engine.Block;
import io.github.linkfgfgui.minimalbackup.engine.BlockFile;
import io.github.linkfgfgui.minimalbackup.engine.AtomicFiles;
import io.github.linkfgfgui.minimalbackup.engine.BlockTable;
import io.github.linkfgfgui.minimalbackup.engine.Checkpoint;
import io.github.linkfgfgui.minimalbackup.engine.Compactor;
import io.github.linkfgfgui.minimalbackup.engine.JsonStore;
import io.github.linkfgfgui.minimalbackup.engine.Log;
import io.github.linkfgfgui.minimalbackup.engine.Resolver;
import io.github.linkfgfgui.minimalbackup.engine.SelfHealing;
import io.github.linkfgfgui.minimalbackup.engine.Snapshot;
import io.github.linkfgfgui.minimalbackup.engine.Timeline;
import io.github.linkfgfgui.minimalbackup.engine.VaultPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;

/**
 * Session-scoped backup runtime: change capture, checkpointing and the sparse read overlay.
 *
 * <p>All disk state lives in {@code <gameDir>/mbackups/<worldKey>}; the engine never touches the
 * world directory itself.
 */
public final class BackupRuntime {
    private static final Log.Logger LOG = Log.LOGGER;
    private static final ThreadLocal<Boolean> COW_READ = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static volatile BackupRuntime instance;

    private final Path worldDir;
    private final String worldKey;
    private final Path vaultRoot;
    private final Path snapshotRoot;
    private final Timeline timeline;
    private volatile Checkpoint checkpoint;
    private final Resolver resolver;
    private final ChangeSetBuffer buffer;
    private final Map<String, BlockTable> tables = new ConcurrentHashMap<>();
    private final Map<TargetKey, Boolean> hasBlock = new ConcurrentHashMap<>();
    private final Set<TargetKey> damaged = ConcurrentHashMap.newKeySet();
    private final Set<TargetKey> cowWarned = ConcurrentHashMap.newKeySet();
    private final AtomicLong coverHits = new AtomicLong();
    private final AtomicLong coverAbsent = new AtomicLong();
    private final AtomicLong passThrough = new AtomicLong();
    private final Object lock = new Object();

    private volatile String armedSlot;
    private volatile boolean restoring;
    /** JSON 物化只做一次：新实例读 level.dat 时触发。 */
    private volatile boolean jsonMaterialized;
    private volatile long lastSnapshotMillis = System.currentTimeMillis();
    private volatile String lastError = "";
    /** level.dat changed while the mod was not running (mod disabled, crash, external tool). */
    private volatile boolean worldChangedOutside;

    private BackupRuntime(Path worldDir, Timeline timeline) {
        this.worldDir = worldDir.toAbsolutePath().normalize();
        Path name = this.worldDir.getFileName();
        if (name == null) {
            throw new IllegalArgumentException("world directory has no name: " + worldDir);
        }
        this.worldKey = name.toString();
        this.vaultRoot = VaultPaths.vaultRoot(MinimalBackup.gameDir(), worldKey);
        this.snapshotRoot = VaultPaths.snapshotRoot(vaultRoot);
        this.timeline = timeline;
        this.checkpoint = new Checkpoint(snapshotRoot, timeline,
                ConfigHolder.values().engineSettings());
        this.resolver = new Resolver(snapshotRoot, timeline);
        this.buffer = new ChangeSetBuffer(
                ConfigHolder.values().integer("backup.maxChangeSetKeys"),
                ConfigHolder.values().longValue("backup.maxChangeSetBytes"));
        this.buffer.setOverflowHandler(this::overflowCheckpoint);
    }

    /** Returns the runtime for this world, loading the timeline on first use. */
    public static BackupRuntime init(Path worldDir) throws IOException {
        BackupRuntime current = instance;
        if (current != null && current.worldDir.equals(worldDir.toAbsolutePath().normalize())) {
            current.refreshConfig();
            return current;
        }
        synchronized (BackupRuntime.class) {
            current = instance;
            if (current != null && current.worldDir.equals(worldDir.toAbsolutePath().normalize())) {
                current.refreshConfig();
                return current;
            }
            Path normalized = worldDir.toAbsolutePath().normalize();
            Files.createDirectories(VaultPaths.snapshotRoot(VaultPaths.vaultRoot(MinimalBackup.gameDir(),
                    normalized.getFileName().toString())));
            Path index = VaultPaths.indexFile(VaultPaths.vaultRoot(MinimalBackup.gameDir(),
                    normalized.getFileName().toString()));
            Timeline timeline = Files.exists(index) ? Timeline.load(index) : Timeline.empty();
            BackupRuntime created = new BackupRuntime(normalized, timeline);
            if (Files.exists(index)) {
                SelfHealing.Report report = new SelfHealing().heal(created.snapshotRoot, timeline);
                if (report.tempFilesDeleted() > 0 || report.filesTruncated() > 0
                        || report.orphanBlocks() > 0 || !report.damagedFiles().isEmpty()) {
                    LOG.warn("self-heal: temp={} truncated={} orphanBlocks={} damaged={}",
                            report.tempFilesDeleted(), report.filesTruncated(),
                            report.orphanBlocks(), report.damagedFiles());
                }
            }
            created.refreshConfig();
            created.resumeMarker();
            created.checkWorldState();
            instance = created;
            return created;
        }
    }

    /**
     * Re-reads the platform config into {@link ConfigHolder} and applies runtime-side values.
     *
     * <p>Safe to call before the loader has loaded the config file: the platform reloader keeps
     * the previous value for any key it cannot read yet, so this never throws.
     */
    public void refreshConfig() {
        ConfigHolder.refresh();
        LOG.info("config{}: enabled={} captureOnRead={} maxKeys={} maxBytes={}",
                ConfigHolder.loaded() ? "" : " (defaults; loader has not loaded the file yet)",
                ConfigHolder.values().bool("backup.enabled"),
                ConfigHolder.values().bool("backup.captureOnRead"),
                ConfigHolder.values().integer("backup.maxChangeSetKeys"),
                ConfigHolder.values().longValue("backup.maxChangeSetBytes"));
        buffer.configure(ConfigHolder.values().integer("backup.maxChangeSetKeys"),
                ConfigHolder.values().longValue("backup.maxChangeSetBytes"));
        this.checkpoint = new Checkpoint(snapshotRoot, timeline,
                ConfigHolder.values().engineSettings());
    }

    private void resumeMarker() {
        Path marker = snapshotRoot.resolve(VaultPaths.RESTORE_MARKER_NAME);
        if (!Files.isRegularFile(marker)) {
            return;
        }
        try {
            String slot = "";
            for (String line : Files.readAllLines(marker, StandardCharsets.UTF_8)) {
                if (line.startsWith("slot=")) {
                    slot = line.substring("slot=".length()).trim();
                }
            }
            if (!slot.isEmpty() && timeline.snapshot(slot) != null) {
                armedSlot = slot;
                restoring = true;
                jsonMaterialized = false;
                LOG.warn("restore marker found; re-armed {} before world load", slot);
            } else {
                LOG.error("restore marker has unknown slot '{}'; manual intervention required", slot);
            }
        } catch (IOException e) {
            LOG.error("cannot read restore marker: {}", e.toString());
        }
    }

    public static BackupRuntime get() {
        return instance;
    }

    public Path worldDir() {
        return worldDir;
    }

    public String worldKey() {
        return worldKey;
    }

    public Path snapshotRoot() {
        return snapshotRoot;
    }

    public Timeline timeline() {
        return timeline;
    }

    public ChangeSetBuffer buffer() {
        return buffer;
    }

    public String armedSlot() {
        return armedSlot;
    }

    public boolean isRestoring() {
        return restoring;
    }

    /** True when a write would actually be captured (backup enabled and no restore armed). */
    public boolean captureEnabled() {
        return !restoring && ConfigHolder.values().bool("backup.enabled");
    }

    public long coverHits() {
        return coverHits.get();
    }

    public long coverAbsent() {
        return coverAbsent.get();
    }

    public String lastError() {
        return lastError;
    }

    public static boolean isCowRead() {
        return COW_READ.get();
    }

    public static <T> T withCowRead(Supplier<T> supplier) {
        boolean previous = COW_READ.get();
        COW_READ.set(Boolean.TRUE);
        try {
            return supplier.get();
        } finally {
            COW_READ.set(previous);
        }
    }

    // ---------------------------------------------------------------- capture

    public void captureChunkReadAt(Path folder, int chunkX, int chunkZ, CompoundTag tag) {
        if (tag == null || restoring || armedSlot != null || isCowRead()
                || !ConfigHolder.values().bool("backup.enabled")
                || !ConfigHolder.values().bool("backup.captureOnRead")) {
            return;
        }
        String key = SnapshotKeys.chunkKey(worldDir, folder, chunkX >> 5, chunkZ >> 5);
        if (key == null) {
            return;
        }
        if (ConfigHolder.values().bool("debug.verboseLogging")) {
            LOG.info("capture read {}#{} ch={},{}", key, SnapshotKeys.chunkSubTarget(chunkX, chunkZ),
                    chunkX, chunkZ);
        }
        captureBaseline(new TargetKey(key, SnapshotKeys.chunkSubTarget(chunkX, chunkZ)),
                Block.TYPE_CHUNK, NbtCodec.toBytes(tag));
    }

    public void captureChunkWrite(Path folder, int chunkX, int chunkZ, CompoundTag tag,
                                  Supplier<CompoundTag> oldReader) {
        if (restoring || !ConfigHolder.values().bool("backup.enabled")) {
            return;
        }
        String key = SnapshotKeys.chunkKey(worldDir, folder, chunkX >> 5, chunkZ >> 5);
        if (key == null) {
            return;
        }
        TargetKey target = new TargetKey(key, SnapshotKeys.chunkSubTarget(chunkX, chunkZ));
        if (ConfigHolder.values().bool("debug.verboseLogging")) {
            LOG.info("capture write {}#{}", key, target.subTarget());
        }
        captureWrite(target, Block.TYPE_CHUNK, tag, oldReader);
    }

    public void captureFileWrite(Path file, CompoundTag tag, Supplier<CompoundTag> oldReader) {
        if (restoring || !ConfigHolder.values().bool("backup.enabled")) {
            return;
        }
        String key = SnapshotKeys.fileKey(worldDir, file);
        if (key == null || VaultPaths.isExcluded(key)) {
            return;
        }
        if (ConfigHolder.values().bool("debug.verboseLogging")) {
            LOG.info("capture write {}", key);
        }
        captureWrite(new TargetKey(key, 0L), Block.TYPE_FILE, tag, oldReader);
    }

    /**
     * Baseline-only capture for the optional full importer: never COW, never overwrite an
     * existing chain. Returns true when this file produced a new baseline entry.
     */
    public boolean captureFullBaselineFile(Path file, CompoundTag tag) {
        if (restoring || armedSlot != null) {
            return false;
        }
        String key = SnapshotKeys.fileKey(worldDir, file);
        if (key == null || VaultPaths.isExcluded(key)) {
            return false;
        }
        TargetKey target = new TargetKey(key, 0L);
        synchronized (lock) {
            if (hasAnyBlock(target) || buffer.hasBaseline(target)) {
                return false;
            }
            buffer.setBaseline(target, Block.TYPE_FILE, NbtCodec.toBytes(tag));
            return true;
        }
    }

    /**
     * Runs the optional full baseline once per vault when {@code backup.fullBaseline} is on and
     * there is no snapshot yet. A marker prevents a second scan after the first one succeeds.
     */
    public void maybeFullBaseline(MinecraftServer server) {
        if (!ConfigHolder.values().bool("backup.fullBaseline") || timeline.size() != 0) {
            return;
        }
        Path marker = snapshotRoot.resolve("full-baseline.done");
        if (Files.exists(marker)) {
            return;
        }
        LOG.warn("starting optional full baseline; the server may stall while region reads run");
        FullBaselineImporter.Report report = FullBaselineImporter.run(this, server);
        if (report.chunksPresent == 0 && report.filesCaptured == 0) {
            LOG.warn("full baseline captured nothing (region files may not be flushed yet); "
                    + "it will run again on the next start or via /backup baseline confirm");
            return;
        }
        try {
            Files.writeString(marker, "chunks=" + report.chunksPresent + " files="
                    + report.filesCaptured + " failures=" + report.failures + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("cannot write full baseline marker: {}", e.toString());
        }
    }

    private void captureWrite(TargetKey target, int type, CompoundTag tag,
                              Supplier<CompoundTag> oldReader) {
        synchronized (lock) {
            if (!hasAnyBlock(target) && !buffer.hasBaseline(target)) {
                if (oldReader == null) {
                    buffer.setBaselineAbsent(target, type);
                } else {
                    try {
                        CompoundTag old = oldReader.get();
                        if (old == null) {
                            buffer.setBaselineAbsent(target, type);
                        } else {
                            buffer.setBaseline(target, type, NbtCodec.toBytes(old));
                            if (ConfigHolder.values().bool("debug.logEveryCow")
                                    || cowWarned.add(target)) {
                                LOG.warn("copy-on-write captured the old value of {}#{}",
                                        target.relPath(), target.subTarget());
                            }
                        }
                    } catch (RuntimeException e) {
                        damaged.add(target);
                        LOG.warn("COW failed for {}#{}: {}", target.relPath(), target.subTarget(),
                                e.toString());
                    }
                }
            }
            if (tag == null) {
                buffer.setLatestAbsent(target, type);
            } else {
                buffer.setLatest(target, type, NbtCodec.toBytes(tag));
            }
        }
    }

    private void captureBaseline(TargetKey target, int type, byte[] data) {
        synchronized (lock) {
            if (hasAnyBlock(target) || buffer.hasBaseline(target)) {
                return;
            }
            buffer.setBaseline(target, type, data);
        }
    }

    // ------------------------------------------------------------------ reads

    public Resolver.Result resolveChunk(Path folder, int chunkX, int chunkZ) {
        String slot = armedSlot;
        if (slot == null) {
            return Resolver.Result.passthrough();
        }
        String key = SnapshotKeys.chunkKey(worldDir, folder, chunkX >> 5, chunkZ >> 5);
        if (key == null) {
            return Resolver.Result.passthrough();
        }
        return resolve(new TargetKey(key, SnapshotKeys.chunkSubTarget(chunkX, chunkZ)),
                Block.TYPE_CHUNK);
    }

    public Resolver.Result resolveFile(Path file) {
        String slot = armedSlot;
        if (slot == null) {
            return Resolver.Result.passthrough();
        }
        String key = SnapshotKeys.fileKey(worldDir, file);
        if (key == null) {
            return Resolver.Result.passthrough();
        }
        // 新实例读 level.dat 时，旧实例的停服保存已经结束；此时把 JSON 写回磁盘，
        // 早于玩家数据/进度被读取。只做一次。
        if (restoring && !jsonMaterialized && "level.dat".equals(key)) {
            materializeJson();
        }
        return resolve(new TargetKey(key, 0L), Block.TYPE_FILE);
    }

    private void materializeJson() {
        jsonMaterialized = true;
        Snapshot target = timeline.snapshot(armedSlot);
        if (target == null) {
            return;
        }
        try {
            int count = JsonStore.materialize(worldDir, snapshotRoot, target.generation());
            LOG.info("restore: materialized {} json file(s) for {}", count, target.slot());
        } catch (IOException | RuntimeException e) {
            LOG.error("restore: cannot materialize json files: {}", e.toString());
        }
    }

    public Resolver.Result resolveData(String dataFolder, String name) {
        String slot = armedSlot;
        if (slot == null) {
            return Resolver.Result.passthrough();
        }
        Path folder = Path.of(dataFolder);
        Path file = folder.resolve(name + ".dat");
        String key = SnapshotKeys.fileKey(worldDir, file);
        if (key == null) {
            return Resolver.Result.passthrough();
        }
        return resolve(new TargetKey(key, 0L), Block.TYPE_FILE);
    }

    private Resolver.Result resolve(TargetKey target, int subTargetType) {
        String slot = armedSlot;
        if (slot == null) {
            return Resolver.Result.passthrough();
        }
        try {
            Resolver.Result result = resolver.resolve(target.relPath(), target.subTarget(),
                    subTargetType, slot);
            switch (result.kind()) {
                case PRESENT -> coverHits.incrementAndGet();
                case ABSENT -> coverAbsent.incrementAndGet();
                case PASSTHROUGH -> passThrough.incrementAndGet();
            }
            return result;
        } catch (IOException | RuntimeException e) {
            lastError = e.toString();
            LOG.error("resolve failed for {}#{}: {}", target.relPath(), target.subTarget(), e.toString());
            return Resolver.Result.passthrough();
        }
    }

    // -------------------------------------------------------------- snapshots

    public Snapshot createSnapshot(String source, String displayName) throws IOException {
        return createSnapshot(source, displayName, "");
    }

    public Snapshot createSnapshot(String source, String displayName, String createdBy)
            throws IOException {
        synchronized (lock) {
            Checkpoint current = checkpoint;
            JsonStore.Plan json = ConfigHolder.values().bool("backup.copyJson")
                    ? JsonStore.plan(worldDir, snapshotRoot, current.settings().zstdLevel())
                    : null;
            if (buffer.isEmpty() && (json == null || json.isEmpty())) {
                throw new IOException("no changes captured since the last snapshot");
            }
            boolean firstSnapshot = timeline.size() == 0;
            Checkpoint.ChangeSet changes = buffer.toChangeSet();
            boolean isDamaged = !damaged.isEmpty();
            Snapshot snapshot = current.create(changes, new Checkpoint.SnapshotInfo()
                    .source(source)
                    .createdBy(createdBy == null ? "" : createdBy)
                    .displayName(displayName == null ? "" : displayName)
                    .worldName(worldKey)
                    .minecraftVersion(MinimalBackup.MINECRAFT)
                    .engineVersion(MinimalBackup.VERSION)
                    .extraBytes(json == null ? 0L : json.logicalBytes(),
                            json == null ? 0L : json.physicalBytes())
                    .damaged(isDamaged));
            if (json != null && !json.isEmpty()) {
                try {
                    long written = JsonStore.write(snapshotRoot, json, snapshot.slot());
                    LOG.info("json: {} file(s) changed, {} compressed bytes", json.entries().size(),
                            written);
                } catch (IOException | RuntimeException e) {
                    LOG.error("json capture failed for {}: {}", snapshot.slot(), e.toString());
                }
            }
            if (firstSnapshot) {
                copyWorldIcon();
            }
            buffer.clear();
            damaged.clear();
            cowWarned.clear();
            tables.clear();
            hasBlock.clear();
            lastSnapshotMillis = System.currentTimeMillis();
            // Optional cover: only an integrated/host client installs this hook; a dedicated
            // server leaves it null and never takes a screenshot.
            ThumbnailBridge.capture(snapshot.slot(), snapshotRoot);
            LOG.info("created {} gen={} entries={} logical={} physical={}", snapshot.slot(),
                    snapshot.generation(), snapshot.entryCount(), snapshot.logicalBytes(),
                    snapshot.physicalBytes());
            enforceRetention();
            return snapshot;
        }
    }

    /** 首个快照时把 icon.png 原样复制到库根；不压缩、不参与回滚。 */
    private void copyWorldIcon() {
        Path source = worldDir.resolve(JsonStore.ICON_NAME);
        Path target = snapshotRoot.resolve(JsonStore.ICON_NAME);
        if (!Files.isRegularFile(source) || Files.exists(target)) {
            return;
        }
        try {
            Files.createDirectories(snapshotRoot);
            AtomicFiles.copy(source, target);
            LOG.info("copied {} to the vault root", JsonStore.ICON_NAME);
        } catch (IOException | RuntimeException e) {
            LOG.warn("cannot copy {}: {}", JsonStore.ICON_NAME, e.toString());
        }
    }

    /**
     * Tombstones the oldest snapshots above the retention limits. The head and the armed slot are
     * never touched, and tombstones keep their generation so later snapshots stay resolvable.
     */
    private void enforceRetention() throws IOException {
        int keep = ConfigHolder.values().integer("backup.retentionKeep");
        long maxBytes = ConfigHolder.values().longValue("backup.retentionMaxBytes");
        List<Snapshot> live = new ArrayList<>();
        for (Snapshot snapshot : timeline.snapshots()) {
            if (!snapshot.tombstone()) {
                live.add(snapshot);
            }
        }
        live.sort(Comparator.comparingInt(Snapshot::generation));
        long total = 0;
        for (Snapshot snapshot : live) {
            total += snapshot.physicalBytes();
        }
        boolean changed = false;
        int index = 0;
        while (index < live.size() - 1
                && (live.size() - index > keep || (maxBytes > 0 && total > maxBytes))) {
            Snapshot oldest = live.get(index);
            if (!oldest.slot().equals(timeline.head()) && !oldest.slot().equals(armedSlot)) {
                timeline.tombstone(oldest.slot());
                total -= oldest.physicalBytes();
                changed = true;
            }
            index++;
        }
        if (changed) {
            timeline.save(VaultPaths.indexFile(vaultRoot));
        }
    }

    private void overflowCheckpoint() {
        try {
            createSnapshot("auto", "change-set limit");
        } catch (IOException e) {
            LOG.error("automatic checkpoint failed: {}", e.toString());
        }
    }

    public List<Snapshot> snapshots() {
        List<Snapshot> list = new ArrayList<>(timeline.snapshots());
        list.sort(Comparator.comparingInt(Snapshot::generation));
        return list;
    }

    /**
     * Called once per server tick; creates the interval snapshot when it is due.
     *
     * <p>The interval is measured from the last snapshot of any kind, so a manual checkpoint
     * postpones the next automatic one instead of firing twice in a row.
     */
    public void tick() {
        if (!ConfigHolder.values().bool("backup.enabled")
                || !ConfigHolder.values().bool("backup.triggerOnInterval")) {
            return;
        }
        long interval = ConfigHolder.values().integer("backup.intervalMinutes") * 60_000L;
        if (System.currentTimeMillis() - lastSnapshotMillis < interval) {
            return;
        }
        synchronized (lock) {
            if (buffer.isEmpty()) {
                return;
            }
        }
        try {
            createSnapshot("interval", "");
        } catch (IOException | RuntimeException e) {
            LOG.warn("interval snapshot failed: {}", e.toString());
            lastSnapshotMillis = System.currentTimeMillis();
        }
    }

    /**
     * Finishes a restore that was armed before the world loaded: opens the write path again and
     * checks the integrity gate. Returns {@code false} when no snapshot read was served, in which
     * case the caller must stop the server so the untouched disk state remains authoritative.
     */
    public boolean completeRestore(MinecraftServer server) {
        String slot;
        synchronized (lock) {
            if (!restoring || armedSlot == null) {
                return true;
            }
            if (coverHits.get() == 0) {
                LOG.error("restore gate failed: {} served no reads; keeping the old world", armedSlot);
                clearRestoreFiles();
                return false;
            }
            restoring = false;
            buffer.clear();
            slot = armedSlot;
            // The gate passed, so the restored snapshot becomes the new head. The next checkpoint
            // must branch off it instead of continuing the abandoned chain.
            timeline.setHead(slot);
            try {
                timeline.save(VaultPaths.indexFile(vaultRoot));
            } catch (IOException e) {
                LOG.error("restored head {} not persisted: {}", slot, e.toString());
            }
        }
        // Never hold the capture lock across a world save: the IOWorker flush calls back into
        // captureWrite and would deadlock with this thread.
        boolean clear = true;
        if (ConfigHolder.values().bool("restore.forceDirty")) {
            try {
                // Materialise the served view before dropping the transaction marker, so a crash
                // in this window resumes the restore instead of leaving a half-written world.
                server.saveEverything(true, true, true);
            } catch (RuntimeException e) {
                clear = false;
                LOG.error("restore force-save failed; keeping restore.marker for the next start: {}",
                        e.toString());
            }
        } else {
            LOG.warn("restore.forceDirty=false: skipped the immediate save; the restored view "
                    + "persists only if the world is saved normally before shutdown");
        }
        synchronized (lock) {
            if (clear) {
                clearRestoreFiles();
            }
            LOG.info("restore materialised: serving {} for this session; coverHits={}", slot,
                    coverHits.get());
        }
        return true;
    }

    /**
     * Server-stopping hook: logs the restore session and, when configured, takes the unload
     * snapshot. Vanilla has already saved the world at this point, so the captured buffer holds
     * the current state.
     */
    public void onServerStopping() {
        synchronized (lock) {
            if (armedSlot != null) {
                LOG.info("restore session ends: slot={} coverHits={} coverAbsent={} pass={}",
                        armedSlot, coverHits.get(), coverAbsent.get(), passThrough.get());
            }
            if (ConfigHolder.values().bool("debug.dumpKeyCoverage")) {
                LOG.info("resolver keys: {}", tables.keySet());
            }
            if (ConfigHolder.values().bool("backup.enabled")
                    && ConfigHolder.values().bool("backup.triggerOnUnload")
                    && !buffer.isEmpty()) {
                try {
                    createSnapshot("unload", "");
                } catch (IOException | RuntimeException e) {
                    LOG.warn("unload snapshot failed: {}", e.toString());
                }
            }
        }
    }

    /**
     * Records the level.dat fingerprint after the final save. The next start compares against it:
     * a different hash means somebody saved the world while the mod was not capturing.
     */
    public void onServerStopped() {
        if (armedSlot != null) {
            // The disk state belongs to the world the restore is replacing; it is not a witness
            // of a save made outside the mod.
            return;
        }
        String hash = levelDataHash(worldDir.resolve("level.dat"));
        if (hash.isEmpty()) {
            return;
        }
        try {
            Files.writeString(snapshotRoot.resolve(VaultPaths.WORLD_STATE_NAME),
                    "levelData=" + hash + "\nsavedAt=" + System.currentTimeMillis() + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            LOG.warn("cannot write world.state: {}", e.toString());
        }
    }

    private void checkWorldState() {
        Path levelData = worldDir.resolve("level.dat");
        Path state = snapshotRoot.resolve(VaultPaths.WORLD_STATE_NAME);
        if (!Files.isRegularFile(levelData) || !Files.isRegularFile(state)) {
            return;
        }
        String expected = "";
        try {
            for (String line : Files.readAllLines(state, StandardCharsets.UTF_8)) {
                if (line.startsWith("levelData=")) {
                    expected = line.substring("levelData=".length()).trim();
                }
            }
        } catch (IOException e) {
            LOG.warn("cannot read world.state: {}", e.toString());
            return;
        }
        String actual = levelDataHash(levelData);
        if (expected.isEmpty() || actual.isEmpty() || expected.equals(actual)) {
            return;
        }
        worldChangedOutside = true;
        if (timeline.size() > 0) {
            LOG.warn("level.dat changed since the last clean shutdown: the world was saved while "
                    + "minimal-backup was not running. Targets edited in that window keep their "
                    + "last captured version, so a restore may not cover them.");
        }
    }

    private static String levelDataHash(Path file) {
        if (!Files.isRegularFile(file)) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Files.readAllBytes(file));
            StringBuilder text = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                text.append(Character.forDigit((b >> 4) & 0xF, 16));
                text.append(Character.forDigit(b & 0xF, 16));
            }
            return text.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            LOG.warn("cannot hash level.dat: {}", e.toString());
            return "";
        }
    }

    private void clearRestoreFiles() {
        try {
            Files.deleteIfExists(snapshotRoot.resolve(VaultPaths.RESTORE_MARKER_NAME));
            Files.deleteIfExists(snapshotRoot.resolve(VaultPaths.REJOIN_MARKER_NAME));
        } catch (IOException e) {
            LOG.error("cannot clear restore marker: {}", e.toString());
        }
    }

    public Snapshot prepareRestore(String slot) throws IOException {
        synchronized (lock) {
            Snapshot target = timeline.snapshot(slot);
            if (target == null) {
                throw new IOException("unknown snapshot: " + slot);
            }
            if (target.tombstone()) {
                throw new IOException("snapshot is deleted: " + slot);
            }
            if (target.damaged()) {
                throw new IOException("snapshot is damaged: " + slot);
            }
            // After a completed restore the session keeps serving that snapshot and stops
            // capturing, so a second restore is allowed and simply replaces the overlay. Only a
            // restore that was armed but never completed blocks another one.
            if (restoring && armedSlot != null && !armedSlot.equals(slot)) {
                throw new IOException("a restore is already in progress: " + armedSlot);
            }
            VerifyReport report = verify(slot);
            if (report.failures > 0) {
                throw new IOException("snapshot failed verification: " + report.failures
                        + " broken target(s); first: " + report.errors.get(0));
            }
            if (!buffer.isEmpty()
                    && ConfigHolder.values().bool("restore.autoCheckpointBeforeRestore")) {
                createSnapshot("pre-restore", "before restore " + slot);
            }
            arm(slot);
            return target;
        }
    }

    public void arm(String slot) throws IOException {
        Snapshot target = timeline.snapshot(slot);
        if (target == null) {
            throw new IOException("unknown snapshot: " + slot);
        }
        armedSlot = slot;
        restoring = true;
        jsonMaterialized = false;
        coverHits.set(0);
        coverAbsent.set(0);
        passThrough.set(0);
        lastError = "";
        if (ConfigHolder.values().bool("restore.restoreMarker")) {
            Path marker = snapshotRoot.resolve(VaultPaths.RESTORE_MARKER_NAME);
            String content = "slot=" + slot + "\nphase=armed\nat="
                    + System.currentTimeMillis() + "\n";
            Files.writeString(marker, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.writeString(snapshotRoot.resolve(VaultPaths.REJOIN_MARKER_NAME), content,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        }
        LOG.warn("restore armed for {}; writes are not captured until disarm", slot);
    }

    public void renameSnapshot(String slot, String displayName) throws IOException {
        synchronized (lock) {
            if (timeline.snapshot(slot) == null) {
                throw new IOException("unknown snapshot: " + slot);
            }
            timeline.rename(slot, displayName);
            timeline.save(VaultPaths.indexFile(vaultRoot));
        }
    }

    public void deleteSnapshot(String slot) throws IOException {
        synchronized (lock) {
            if (timeline.snapshot(slot) == null) {
                throw new IOException("unknown snapshot: " + slot);
            }
            if (slot.equals(armedSlot)) {
                throw new IOException("cannot delete the armed snapshot: " + slot);
            }
            timeline.tombstone(slot);
            timeline.save(VaultPaths.indexFile(vaultRoot));
        }
    }

    /** Result of a chain/block verification pass. */
    public static final class VerifyReport {
        public long targets;
        public long present;
        public long absent;
        public long passThrough;
        public long failures;
        public final List<String> errors = new ArrayList<>();
    }

    /**
     * Reconstructs every block visible to {@code slot} and reports unreadable chains. Targets with
     * no block for this snapshot are counted as pass-through, which is not a failure.
     */
    public VerifyReport verify(String slot) throws IOException {
        Snapshot target = timeline.snapshot(slot);
        if (target == null) {
            throw new IOException("unknown snapshot: " + slot);
        }
        if (target.tombstone()) {
            throw new IOException("snapshot is deleted: " + slot);
        }
        VerifyReport report = new VerifyReport();
        for (Path file : VaultPaths.listZpacks(snapshotRoot)) {
            String rel = VaultPaths.keyForZpack(snapshotRoot, file);
            BlockTable table;
            try {
                table = BlockFile.loadTable(file, -1L);
            } catch (IOException | RuntimeException e) {
                report.failures++;
                report.errors.add(rel + ": " + e.getMessage());
                continue;
            }
            for (long subTarget : table.subTargets(Block.TYPE_FILE)) {
                verifyTarget(rel, table, subTarget, Block.TYPE_FILE, slot, report);
            }
            for (long subTarget : table.subTargets(Block.TYPE_CHUNK)) {
                verifyTarget(rel, table, subTarget, Block.TYPE_CHUNK, slot, report);
            }
        }
        long[] jsonCounters = new long[4];
        List<String> jsonErrors = new ArrayList<>();
        JsonStore.verify(snapshotRoot, target.generation(), jsonCounters, jsonErrors,
                Math.max(0, 20 - report.errors.size()));
        report.targets += jsonCounters[0];
        report.absent += jsonCounters[1];
        report.present += jsonCounters[2];
        report.failures += jsonCounters[3];
        report.errors.addAll(jsonErrors);
        return report;
    }

    private void verifyTarget(String rel, BlockTable table, long subTarget, int subTargetType,
                              String slot, VerifyReport report) {
        report.targets++;
        try {
            Resolver.Result result = resolver.resolve(rel, table, subTarget, subTargetType, slot);
            switch (result.kind()) {
                case PRESENT -> report.present++;
                case ABSENT -> report.absent++;
                case PASSTHROUGH -> report.passThrough++;
            }
        } catch (IOException | RuntimeException e) {
            report.failures++;
            if (report.errors.size() < 20) {
                report.errors.add(rel + "#" + subTarget + "[" + subTargetType + "]: "
                        + e.getMessage());
            }
        }
    }

    /** Drops blocks that no live generation can reach. */
    public Compactor.Report collectGarbage() throws IOException {
        synchronized (lock) {
            if (armedSlot != null) {
                throw new IOException("cannot compact while a restore is armed: " + armedSlot);
            }
            Compactor.Report report = new Compactor(snapshotRoot, timeline).compact();
            Set<Integer> live = timeline.liveGenerations();
            int oldestLive = live.isEmpty() ? Integer.MAX_VALUE : Collections.min(live);
            int removed = JsonStore.collectGarbage(snapshotRoot, oldestLive);
            if (removed > 0) {
                LOG.info("json gc: removed {} old version(s)", removed);
            }
            return report;
        }
    }

    public void disarm() throws IOException {
        armedSlot = null;
        restoring = false;
        jsonMaterialized = false;
        Files.deleteIfExists(snapshotRoot.resolve(VaultPaths.RESTORE_MARKER_NAME));
        Files.deleteIfExists(snapshotRoot.resolve(VaultPaths.REJOIN_MARKER_NAME));
        LOG.info("restore disarmed; coverHits={} coverAbsent={} pass={}", coverHits.get(),
                coverAbsent.get(), passThrough.get());
    }

    public boolean hasAnyBlock(TargetKey target) {
        return hasBlock.computeIfAbsent(target, key -> {
            Path file = VaultPaths.zpackFor(snapshotRoot, key.relPath());
            if (!Files.exists(file)) {
                return Boolean.FALSE;
            }
            try {
                BlockTable table = tables.computeIfAbsent(key.relPath(), rel -> {
                    try {
                        Long cache = timeline.cacheOffset(rel);
                        return BlockFile.loadTable(VaultPaths.zpackFor(snapshotRoot, rel),
                                cache == null ? -1L : cache);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
                return !table.blocksFor(key.subTarget()).isEmpty();
            } catch (RuntimeException e) {
                LOG.warn("block table load failed for {}: {}", key.relPath(), e.toString());
                return Boolean.FALSE;
            }
        });
    }

    /** True when the armed restore served at least one block from the snapshot. */
    public boolean restoreServedSnapshot() {
        return coverHits.get() > 0;
    }

    /**
     * A restore that never served a snapshot block looks successful on the client but changed
     * nothing. Callers must surface this instead of reporting a completed rollback.
     */
    public String restoreVerdict() {
        if (armedSlot == null) {
            return "no restore armed";
        }
        if (coverHits.get() == 0) {
            return "restore served no snapshot blocks (coverHits=0)";
        }
        return "restore served " + coverHits.get() + " blocks";
    }

    public String statusText() {
        Snapshot head = timeline.head().isEmpty() ? null : timeline.snapshot(timeline.head());
        long vaultBytes = 0;
        if (Files.isDirectory(snapshotRoot)) {
            try (var stream = Files.walk(snapshotRoot)) {
                vaultBytes = stream.filter(Files::isRegularFile).mapToLong(path -> {
                    try {
                        return Files.size(path);
                    } catch (IOException ignored) {
                        return 0L;
                    }
                }).sum();
            } catch (IOException ignored) {
                // A status call must never fail because a file vanished during the walk.
            }
        }
        return "world=" + worldKey + " snapshots=" + timeline.size() + " head=" + timeline.head()
                + " buffered=" + buffer.size() + " bufferedBytes=" + buffer.bytes()
                + " vaultBytes=" + vaultBytes
                + (head == null ? "" : " last=" + head.slot() + "/" + head.source()
                        + "@" + head.savedAt())
                + " armed=" + (armedSlot == null ? "no" : armedSlot)
                + " coverHits=" + coverHits.get() + " coverAbsent=" + coverAbsent.get()
                + " pass=" + passThrough.get()
                + (worldChangedOutside ? " externalSave=yes" : "")
                + (lastError.isEmpty() ? "" : " lastError=" + lastError);
    }

}
