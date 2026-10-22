# Modded-save fixtures (AE2 / Mekanism)

Three real chunk pairs from a 1.21.1 NeoForge modpack save, used by the modded closure check.
They are **not** part of the 29-pair performance set and are not a volume benchmark.

Source: a dedicated 1.21.1 NeoForge server running the `NAST easy c1.9.4` pack
(`appliedenergistics2-19.2.17`, `Mekanism-1.21.1-10.7.19.85`, `create-1.21.1-6.0.10`). Create,
AE2 and Mekanism blocks were placed with `/setblock` between two `/backup create` calls; the last
two versions of each target were reconstructed from the resulting vault with
`bench/tools/ExtractModdedPairs.java` (each block re-checked against its XXH64) on 2026-10-21.

Each pair:

* `*.old.nbt` — the decompressed chunk from the earlier snapshot.
* `*.new.nbt` — the same target from the later snapshot.

All three pairs contain Create and Mekanism block entities; `chunk_0_0` also contains the placed
AE2 blocks. The modded subtrees are real data, so the closure assertion exercises the parser,
lifter, palette diff and the opaque `byte[]` handling on modded NBT.

```bash
FIXTURES=bench/fixtures-modded ./bench/codec-bench.sh zstd/1
```
