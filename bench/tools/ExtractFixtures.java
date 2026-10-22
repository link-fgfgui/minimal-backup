import io.github.linkfgfgui.minimalbackup.engine.Block;
import io.github.linkfgfgui.minimalbackup.engine.BlockFile;
import io.github.linkfgfgui.minimalbackup.engine.BlockTable;
import io.github.linkfgfgui.minimalbackup.engine.Snapshot;
import io.github.linkfgfgui.minimalbackup.engine.Timeline;
import io.github.linkfgfgui.minimalbackup.engine.VaultPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Fixture extractor for {@code bench/fixtures}: reconstructs one or two versions of a target out
 * of a real vault and writes them as {@code *.old.nbt} / {@code *.new.nbt}.
 *
 * <p>Usage: {@code java ExtractFixtures <vaultRoot> <outputDir> <label> [maxChunkPairs]
 * [keyPrefix] [minChunkBytes] [fromSlot toSlot] [chunkBox]}.
 *
 * <p>Without the two slots it emits each target's last two stored versions. With
 * {@code fromSlot} and {@code toSlot} it emits only targets whose reconstructed state differs
 * between those two snapshots, which is what a multi-phase capture chain needs: the "last two"
 * rule would otherwise re-emit every earlier pair after a later snapshot that touched nothing.
 * The two slots must be consecutive ({@code to.parentSlot == from}); an unrelated snapshot between
 * them would make the generation-based lookup ambiguous. {@code chunkBox} is an optional
 * {@code minX,minZ,maxX,maxZ} filter on chunk coordinates; prefix it with {@code !} to keep only
 * chunks outside the box. It attributes a phase's writes to a known build area versus tick noise.
 */
public final class ExtractFixtures {
    private ExtractFixtures() {
    }

    public static void main(String[] args) throws Exception {
        Path vaultRoot = Paths.get(args[0]);
        Path vault = VaultPaths.snapshotRoot(vaultRoot);
        Path out = Paths.get(args[1]);
        String label = args[2];
        int maxChunkPairs = args.length > 3 ? Integer.parseInt(args[3]) : 4;
        String keyPrefix = args.length > 4 ? args[4] : "";
        int minChunkBytes = args.length > 5 ? Integer.parseInt(args[5]) : 1024;
        String fromSlot = args.length > 6 ? args[6] : "";
        String toSlot = args.length > 7 ? args[7] : "";
        String chunkBox = args.length > 8 ? args[8] : "";
        boolean hasChunkBox = !chunkBox.isEmpty();
        boolean boxExclude = false;
        if (hasChunkBox && chunkBox.startsWith("!")) {
            boxExclude = true;
            chunkBox = chunkBox.substring(1);
            if (chunkBox.isEmpty()) {
                throw new IllegalArgumentException("chunkBox is empty after '!'");
            }
            hasChunkBox = !chunkBox.isEmpty();
        }
        int boxMinX = 0;
        int boxMinZ = 0;
        int boxMaxX = 0;
        int boxMaxZ = 0;
        if (hasChunkBox) {
            String[] parts = chunkBox.split(",");
            if (parts.length != 4) {
                throw new IllegalArgumentException("chunkBox must be minX,minZ,maxX,maxZ");
            }
            boxMinX = Integer.parseInt(parts[0].trim());
            boxMinZ = Integer.parseInt(parts[1].trim());
            boxMaxX = Integer.parseInt(parts[2].trim());
            boxMaxZ = Integer.parseInt(parts[3].trim());
            if (boxMinX > boxMaxX || boxMinZ > boxMaxZ) {
                throw new IllegalArgumentException("chunkBox min is greater than max: " + chunkBox);
            }
        }
        Timeline timeline = Timeline.load(VaultPaths.indexFile(vaultRoot));
        int fromGen = -1;
        int toGen = -1;
        if (!fromSlot.isEmpty() || !toSlot.isEmpty()) {
            if (fromSlot.isEmpty() || toSlot.isEmpty()) {
                throw new IllegalArgumentException("from and to slot must be given together");
            }
            Snapshot from = timeline.snapshot(fromSlot);
            Snapshot to = timeline.snapshot(toSlot);
            if (from == null || to == null) {
                throw new IllegalArgumentException("unknown snapshot slot: "
                        + (from == null ? fromSlot : toSlot));
            }
            fromGen = from.generation();
            toGen = to.generation();
            if (fromGen >= toGen) {
                throw new IllegalArgumentException("from slot must be older than to slot");
            }
            if (!to.parentSlot().equals(fromSlot)) {
                throw new IllegalArgumentException("slots must be consecutive snapshots: "
                        + fromSlot + " -> " + toSlot);
            }
        }
        Files.createDirectories(out);
        if (fromGen >= 0) {
            System.out.println("range " + fromSlot + "(" + fromGen + ") -> "
                    + toSlot + "(" + toGen + ")");
        }
        int chunks = 0;
        int files = 0;
        for (String key : timeline.cacheKeys()) {
            Path zpack = VaultPaths.zpackFor(vault, key);
            if (!Files.exists(zpack) || !key.startsWith(keyPrefix)) {
                continue;
            }
            Long cache = timeline.cacheOffset(key);
            BlockTable table = BlockFile.loadTable(zpack, cache == null ? -1L : cache);
            List<Long> targets = new ArrayList<>();
            for (Block block : table.blocks()) {
                if (!targets.contains(block.subTarget())) {
                    targets.add(block.subTarget());
                }
            }
            for (long target : targets) {
                List<Block> all = table.blocksFor(target);
                Block oldBlock;
                Block newBlock;
                if (fromGen >= 0) {
                    oldBlock = lastAtOrBefore(all, fromGen);
                    newBlock = lastAtOrBefore(all, toGen);
                    if (oldBlock == null || newBlock == null
                            || oldBlock.mode() == Block.MODE_ABSENT
                            || newBlock.mode() == Block.MODE_ABSENT
                            || oldBlock.number() == newBlock.number()) {
                        continue;
                    }
                } else {
                    List<Block> chain = new ArrayList<>();
                    for (Block block : all) {
                        if (block.mode() != Block.MODE_ABSENT) {
                            chain.add(block);
                        }
                    }
                    if (chain.size() < 2) {
                        continue;
                    }
                    oldBlock = chain.get(chain.size() - 2);
                    newBlock = chain.get(chain.size() - 1);
                }
                boolean chunk = newBlock.subTargetType() == Block.TYPE_CHUNK;
                if (chunk && hasChunkBox && (inChunkBox(target,
                        boxMinX, boxMinZ, boxMaxX, boxMaxZ) == boxExclude)) {
                    continue;
                }
                if (chunk && chunks >= maxChunkPairs) {
                    continue;
                }
                if (!chunk && files >= 8) {
                    continue;
                }
                if (chunk && (oldBytesLenMissing(oldBlock, newBlock, minChunkBytes))) {
                    continue;
                }
                byte[] oldBytes;
                byte[] newBytes;
                try {
                    oldBytes = BlockFile.reconstruct(zpack, table, oldBlock);
                    newBytes = BlockFile.reconstruct(zpack, table, newBlock);
                } catch (IOException e) {
                    System.err.println("skip " + key + " " + target + ": " + e);
                    continue;
                }
                if (Arrays.equals(oldBytes, newBytes) || oldBytes.length < 64 || newBytes.length < 64) {
                    continue;
                }
                String targetName = chunk
                        ? "chunk_" + (int) target + "_" + (int) (target >> 32)
                        : "file";
                String base = label + "__" + key.replace('/', '_').replace('\\', '_')
                        + "__" + targetName;
                write(out.resolve(base + ".old.nbt"), oldBytes);
                write(out.resolve(base + ".new.nbt"), newBytes);
                System.out.println(base + " old=" + oldBytes.length + " new=" + newBytes.length);
                if (chunk) {
                    chunks++;
                } else {
                    files++;
                }
            }
        }
        System.out.println("chunks=" + chunks + " files=" + files);
    }

    private static boolean inChunkBox(long target, int minX, int minZ, int maxX, int maxZ) {
        int x = (int) target;
        int z = (int) (target >> 32);
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    /** Last block written at or before {@code generation}; null when the target did not exist. */
    private static Block lastAtOrBefore(List<Block> blocks, int generation) {
        Block found = null;
        for (Block block : blocks) {
            if (block.generation() <= generation) {
                found = block;
            }
        }
        return found;
    }

    private static boolean oldBytesLenMissing(Block oldBlock, Block newBlock, int minBytes) {
        return oldBlock.originalLength() < minBytes || newBlock.originalLength() < minBytes;
    }

    private static void write(Path path, byte[] data) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, data);
    }
}
