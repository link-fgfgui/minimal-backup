# Bench fixtures — provenance

Raw (pre-canonicalisation) bytes used by `bench/codec-bench.sh`. Every pair is the last two
stored versions of one target in a local development vault, reconstructed with
`BlockFile.reconstruct` by `bench/tools/ExtractFixtures.java`. Nothing here is hand-written
except `opaque/fake-level.dat`.

Extracted on 2026-10-21 from these vaults (the 1.20.1 `level.dat` pair from the live closure run):

| Directory | Vault | Node | Worlds |
| --- | --- | --- | --- |
| `1.21.1-neoforge/` | `run/mbackups/testworld` | NeoForge 1.21.1 | 4 block chunks from `region/r.0.0.mca`, `level.dat`, `data/raids.dat` |
| `1.20.1-forge/` | `run/mbackups/forgeworld` | Forge 1.20.1 | 4 block chunks from `region/r.-1.0.mca`, `data/random_sequences.dat`, `data/raids.dat` |
| `1.20.1-forge/` (`level.dat`) | `run/mbackups/closure1201` | Forge 1.20.1, 2026-10-21 closure run | post-F2 `level.dat` pair (179,764 B each) |
| `1.21.1-fabric/` | `run/mbackups/testworldfabric1211` | Fabric 1.21.1 | 2 block chunks from `region/r.0.0.mca`, `level.dat`, `data/raids.dat` |
| `1.20.1-fabric/` | `run/mbackups/fabric1201` | Fabric 1.20.1 | 2 block chunks from `region/r.0.0.mca`, `level.dat`, `data/raids.dat` |

File names encode `<node>__<vault path>__<target>`: `chunk_<x>_<z>` is a packed ChunkPos,
`file` is the whole-file target (sub-target 0). `*.old.nbt` is the older stored version,
`*.new.nbt` the newer one. Both are exactly the bytes the engine stored, so they start with
`0A 00 00` (TAG_Compound + empty root name) and pass the canonicalisation sniff gate.

## Version caveats

* The 1.20.1 `level.dat` pair was replaced on 2026-10-21 with two versions from the closure run
  (`run/mbackups/closure1201`): both are 179,764 bytes and include Forge's `fml` subtree, which
  confirms the F2 RETURN read-back fix. The earlier 2.9 KB pre-F2 pair is gone.
* `raids.dat` pairs are 76 bytes; they are included for completeness (small-file overhead), not
  as representative deltas.
* Chunks were written by the real server, so they contain real paletted containers; no fixture
  was synthesised or reserialised.

## Opaque fixtures

`opaque/region_r.0.-1.mca` is a 12,288-byte region file (8 KiB sector table, no chunks) copied
from `run/fabricworld/poi/r.0.-1.mca`. `opaque/fake-level.dat` is the 20-byte ASCII payload
`benchmark level data`, the same shape as the fixture in `build.sh`'s CLI smoke test. Neither
passes the NBT sniff gate; `canonicalize` must return them byte-for-byte.

## Regenerating

```bash
javac --release 17 -cp "build/classes:$ZSTD_JAR" -d build/bench-tools bench/tools/ExtractFixtures.java
java -cp "build/bench-tools:build/classes:$ZSTD_JAR" ExtractFixtures <vaultRoot> <outDir> <label>     <maxChunkPairs> <keyPrefix> <minChunkBytes>
```

The extractor is a development tool; it is not compiled by `build.sh` and not shipped.
