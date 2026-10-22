import io.github.linkfgfgui.minimalbackup.customast.zstd.AddonCodec;
import io.github.linkfgfgui.minimalbackup.engine.Block;
import io.github.linkfgfgui.minimalbackup.engine.BlockFile;
import io.github.linkfgfgui.minimalbackup.engine.BlockTable;
import io.github.linkfgfgui.minimalbackup.engine.VaultPaths;
import io.github.linkfgfgui.minimalbackup.engine.Xxh64;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Extracts real successive pairs from a customast-zstd vault (core Timeline would reject it). */
public class ExtractModdedPairs {
  public static void main(String[] a) throws Exception {
    Path snapshotRoot = Paths.get(a[0]);
    Path out = Paths.get(a[1]);
    String label = a[2];
    int maxChunks = a.length > 3 ? Integer.parseInt(a[3]) : 6;
    Properties p = new Properties();
    try (var in = Files.newBufferedReader(snapshotRoot.resolve("index.properties"))) { p.load(in); }
    Files.createDirectories(out);
    int chunks = 0, files = 0;
    for (String key : new TreeSet<>(p.stringPropertyNames())) {
      if (!key.startsWith("cache.")) continue;
      String rel = key.substring("cache.".length());
      Path zpack = VaultPaths.zpackFor(snapshotRoot, rel);
      if (!Files.exists(zpack)) continue;
      long cache = Long.parseLong(p.getProperty(key).trim());
      BlockTable table = BlockFile.loadTable(zpack, cache);
      List<Long> targets = new ArrayList<>();
      for (Block b : table.blocks()) if (!targets.contains(b.subTarget())) targets.add(b.subTarget());
      for (long target : targets) {
        List<Block> chain = new ArrayList<>();
        for (Block b : table.blocksFor(target)) if (b.mode() != Block.MODE_ABSENT) chain.add(b);
        if (chain.size() < 2) continue;
        Block ob = chain.get(chain.size() - 2), nb = chain.get(chain.size() - 1);
        boolean chunk = nb.subTargetType() == Block.TYPE_CHUNK;
        if (chunk && chunks >= maxChunks) continue;
        if (!chunk && files >= 6) continue;
        byte[] o, n;
        try { o = reconstruct(zpack, table, ob); n = reconstruct(zpack, table, nb); }
        catch (IOException e) { System.err.println("skip " + rel + ": " + e); continue; }
        if (Arrays.equals(o, n) || o.length < 64 || n.length < 64) continue;
        String name = chunk ? "chunk_" + (int) target + "_" + (int) (target >> 32) : "file";
        String base = label + "__" + rel.replace('/', '_') + "__" + name;
        Files.write(out.resolve(base + ".old.nbt"), o);
        Files.write(out.resolve(base + ".new.nbt"), n);
        int create = count(n, "create:");
        System.out.println(base + " old=" + o.length + " new=" + n.length + " create=" + create);
        if (chunk) chunks++; else files++;
      }
    }
    System.out.println("chunks=" + chunks + " files=" + files);
  }

  static int count(byte[] hay, String s) {
    byte[] n = s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    int c = 0;
    outer: for (int i = 0; i + n.length <= hay.length; i++) {
      for (int j = 0; j < n.length; j++) if (hay[i + j] != n[j]) continue outer;
      c++;
    }
    return c;
  }

  static byte[] reconstruct(Path file, BlockTable table, Block block) throws IOException {
    List<Block> chain = new ArrayList<>();
    Block cur = block;
    while (true) {
      chain.add(cur);
      if (cur.dictionary() == 0) break;
      Block prev = table.byNumber(cur.dictionary());
      if (prev == null) throw new IOException("missing dictionary");
      cur = prev;
      if (chain.size() > 100000) throw new IOException("cycle");
    }
    Collections.reverse(chain);
    byte[] data = null;
    for (Block step : chain) {
      byte[] payload = BlockFile.readPayload(file, step);
      data = step.mode() == Block.MODE_FULL
          ? AddonCodec.decompress(payload, null, step.originalLength(), step.subTargetType())
          : AddonCodec.decompress(payload, data, step.originalLength(), step.subTargetType());
      if (data.length != step.originalLength()) throw new IOException("length mismatch");
      if (Xxh64.hash(data) != step.hash()) throw new IOException("hash mismatch");
    }
    return data;
  }
}
