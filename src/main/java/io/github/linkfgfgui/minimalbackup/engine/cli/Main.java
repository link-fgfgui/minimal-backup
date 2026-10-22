package io.github.linkfgfgui.minimalbackup.engine.cli;

import io.github.linkfgfgui.minimalbackup.engine.Compactor;
import io.github.linkfgfgui.minimalbackup.engine.Log;
import io.github.linkfgfgui.minimalbackup.engine.Rollback;
import io.github.linkfgfgui.minimalbackup.engine.SelfHealing;
import io.github.linkfgfgui.minimalbackup.engine.Snapshot;
import io.github.linkfgfgui.minimalbackup.engine.Timeline;
import io.github.linkfgfgui.minimalbackup.engine.VaultPaths;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 独立命令行入口：不依赖 Minecraft 或任何 loader，直接对快照库列快照 / 回滚 / 压实 / 自愈。
 *
 * <pre>
 * list     &lt;gameDir&gt; &lt;worldKey&gt;
 * rollback &lt;gameDir&gt; &lt;worldKey&gt; &lt;slot&gt; &lt;targetDir&gt;
 * compact  &lt;gameDir&gt; &lt;worldKey&gt;
 * heal     &lt;gameDir&gt; &lt;worldKey&gt;
 * </pre>
 *
 * <p>{@code rollback} 把运行时 vault 的区块块重建为 {@code .mca}，并恢复整文件目标；
 * targetDir 里已有的 region 文件作为基底，未覆盖的区块保持不变。
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        int code = run(args);
        if (code != 0) {
            System.exit(code);
        }
    }

    /** 执行命令并返回退出码；不调用 {@link System#exit(int)}。 */
    public static int run(String[] args) {
        Log.install((level, message) -> System.err.println("[" + level + "] " + message));
        try {
            return dispatch(args);
        } catch (Exception e) {
            System.err.println("error: " + e.getMessage());
            e.printStackTrace(System.err);
            return 1;
        }
    }

    private static int dispatch(String[] args) throws IOException {
        if (args.length == 0) {
            usage();
            return 2;
        }
        String command = args[0];
        switch (command) {
            case "list":
                return list(args);
            case "rollback":
                return rollback(args);
            case "compact":
                return compact(args);
            case "heal":
                return heal(args);
            case "help":
            case "--help":
            case "-h":
                usage();
                return 0;
            default:
                System.err.println("unknown command: " + command);
                usage();
                return 2;
        }
    }

    private static int list(String[] args) throws IOException {
        if (args.length < 3) {
            usage();
            return 2;
        }
        Path vault = VaultPaths.vaultRoot(Paths.get(args[1]), args[2]);
        Timeline timeline = Timeline.load(VaultPaths.indexFile(vault));
        List<Snapshot> snapshots = new ArrayList<>(timeline.snapshots());
        snapshots.sort(Comparator.comparingInt(Snapshot::generation));
        System.out.println("slot\tgen\tparent\tsavedAt\tdamaged\ttombstone\tentries\tlogical\tphysical\tname");
        for (Snapshot snapshot : snapshots) {
            System.out.println(snapshot.slot()
                    + "\t" + snapshot.generation()
                    + "\t" + snapshot.parentSlot()
                    + "\t" + snapshot.savedAt()
                    + "\t" + snapshot.damaged()
                    + "\t" + snapshot.tombstone()
                    + "\t" + snapshot.entryCount()
                    + "\t" + snapshot.logicalBytes()
                    + "\t" + snapshot.physicalBytes()
                    + "\t" + snapshot.displayName());
        }
        System.out.println("head=" + timeline.head() + " gen=" + timeline.peekGen());
        return 0;
    }

    private static int rollback(String[] args) throws IOException {
        if (args.length < 5) {
            usage();
            return 2;
        }
        Path gameDir = Paths.get(args[1]);
        String worldKey = args[2];
        String slot = args[3];
        Path targetDir = Paths.get(args[4]);
        Path vault = VaultPaths.vaultRoot(gameDir, worldKey);
        Path snapshotRoot = VaultPaths.snapshotRoot(vault);
        Timeline timeline = Timeline.load(VaultPaths.indexFile(vault));
        Snapshot target = timeline.snapshot(slot);
        if (target == null) {
            System.err.println("unknown snapshot: " + slot);
            return 2;
        }
        if (target.damaged()) {
            System.err.println("snapshot is damaged, strict restore refused: " + slot);
            return 4;
        }
        if (target.tombstone()) {
            System.err.println("snapshot is deleted: " + slot);
            return 2;
        }
        Rollback.Report report = new Rollback(snapshotRoot, timeline, targetDir).run(target);
        System.out.println("restored=" + report.filesRestored()
                + " deleted=" + report.filesDeleted()
                + " skipped=" + report.filesSkipped()
                + " regions=" + report.regionsRebuilt()
                + " chunks=" + report.chunksWritten()
                + " removed=" + report.chunksRemoved()
                + " preserved=" + report.chunksPreserved()
                + " dropped=" + report.chunksDropped()
                + " external=" + report.externalChunks()
                + " warnings=" + report.warnings());
        return 0;
    }

    private static int compact(String[] args) throws IOException {
        if (args.length < 3) {
            usage();
            return 2;
        }
        Path vault = VaultPaths.vaultRoot(Paths.get(args[1]), args[2]);
        Path snapshotRoot = VaultPaths.snapshotRoot(vault);
        Timeline timeline = Timeline.load(VaultPaths.indexFile(vault));
        Compactor.Report report = new Compactor(snapshotRoot, timeline).compact();
        System.out.println("scanned=" + report.filesScanned()
                + " compacted=" + report.filesCompacted()
                + " deleted=" + report.filesDeleted()
                + " skipped=" + report.filesSkipped()
                + " blocksScanned=" + report.blocksScanned()
                + " blocksKept=" + report.blocksKept()
                + " blocksDropped=" + report.blocksDropped());
        return 0;
    }

    private static int heal(String[] args) throws IOException {
        if (args.length < 3) {
            usage();
            return 2;
        }
        Path vault = VaultPaths.vaultRoot(Paths.get(args[1]), args[2]);
        Path snapshotRoot = VaultPaths.snapshotRoot(vault);
        Timeline timeline = Timeline.load(VaultPaths.indexFile(vault));
        SelfHealing.Report report = new SelfHealing().heal(snapshotRoot, timeline);
        System.out.println("tempDeleted=" + report.tempFilesDeleted()
                + " truncated=" + report.filesTruncated()
                + " bytesTruncated=" + report.bytesTruncated()
                + " orphanBlocks=" + report.orphanBlocks()
                + " damaged=" + report.damagedFiles().size());
        for (String damaged : report.damagedFiles()) {
            System.err.println("damaged: " + damaged);
        }
        return report.damagedFiles().isEmpty() ? 0 : 3;
    }

    private static void usage() {
        System.out.println("usage:");
        System.out.println("  list     <gameDir> <worldKey>");
        System.out.println("  rollback <gameDir> <worldKey> <slot> <targetDir>");
        System.out.println("  compact  <gameDir> <worldKey>");
        System.out.println("  heal     <gameDir> <worldKey>");
    }
}
