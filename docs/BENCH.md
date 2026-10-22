# Benchmarks

Measured on the development machine, not a controlled lab. Treat the numbers as orders of
magnitude, not guarantees.

> **Historical addon columns.** The codec addons (`hdiff-lzma2/1`, `customast-zstd/1`,
> `customast-lzma/1`) are no longer maintained and are not part of this repository. Their columns
> are kept here as a record of what was measured, not as a supported configuration; those runs
> predate the `CodecProvider` API and used mixins. The core `zstd/1` columns are current.

> **Version boundary.** Every number in "Per-chunk hook path" below was measured **before**
> canonicalisation landed: the stored bytes were the captured bytes, not canonicalise-and-write
> bytes. After that change the engine stores sort-keyed compounds, so whole-vault ratios are
> expected to improve but are not comparable with the table below without re-running it. The
> `logicalBytes` column also changes meaning: it counts **canonical** bytes (the engine does not
> store a second "raw capture size" field), so it can differ from the on-disk file size of the same
> payload.

## Environment

| Item | Value |
| --- | --- |
| CPU | 12th Gen Intel Core i5-12450H (12 threads) |
| Disk | NVMe, ext4 |
| JDK | Zulu 25.0.4 |
| zstd | zstd-jni 1.5.7-11, level 3 |
| Engine | commit state at M0.5 |

## Per-chunk hook path

From the M0.5 dedicated-server runs (real worlds, snapshots created through `/backup create`):

| Node | Snapshot | Entries | Logical | Physical | Physical vs full |
| --- | --- | --- | --- | --- | --- |
| 1.21.1 NeoForge | full (S1) | 195 | 12,616,150 B | 1,555,810 B | 100% |
| 1.21.1 NeoForge | delta (S3, level.dat only) | 10 | — | 202 B | 0.013% |
| 1.20.1 Forge | full (S1) | 2,433 | 67,167,015 B | 8,501,740 B | 100% |
| 1.20.1 Forge | delta (S3) | 22 | 225,819 B | 46,924 B | 0.55% |
| 1.20.1 Forge | delta (S4) | 12 | 148,502 B | 32,376 B | 0.38% |
| 1.21.1 Fabric | full (S1) | 98 | 3,581,353 B | 627,078 B | 100% |
| 1.21.1 Fabric | delta (S5) | 29 | 1,243,219 B | 1,496 B | 0.24% |
| 1.20.1 Fabric | full (S1) | 1,216 | 32,361,202 B | 6,109,752 B | 100% |
| 1.20.1 Fabric | delta (S3) | 81 | 261,122 B | 43,976 B | 0.72% |

The first snapshot of a world is the expensive one; later snapshots are dominated by whatever the
server actually rewrote. The 1.20.1 Forge S2 delta (195 entries, 2.38 MB) is a spawn-chunk
rewrite after a fresh world load, not a steady-state cost.

## Codec bench (`bench/codec-bench.sh`, zstd/1 baseline)

The fixture set is 29 real pairs (12 block chunks, 16 small whole files, one post-F2 1.20.1
`level.dat`) reconstructed from five local dev vaults; provenance and caveats are in
`bench/fixtures/README.md`. Protocol: canonicalise both sides, compress FULL, then align the new
tree to the old base and compress DELTA with the old canonical bytes as the zstd dictionary.
Timings are the minimum of 3 measured rounds after 2 warm-ups, per target, JDK 25.0.4, zstd
level 3.

| Fixture family | Pairs | raw size | DELTA p50 / p95 | FULL p50 / p95 | canonicalise p50 / p95 | encode p50 / p95 |
| --- | --- | --- | --- | --- | --- | --- |
| block chunk (`TYPE_CHUNK`) | 12 | 35–71 KB | 25 B / 50 B | 8,079 B / 13,182 B | 0.85–1.03 ms / 1.4–2.3 ms | 2.1–2.5 ms / 3.1–3.6 ms |
| small whole file (`TYPE_FILE`) | 16 | 76 B–3.3 KB | 20 B / 359 B | 85 B / 1,700 B | 5–21 µs / 435 µs | 12–106 µs / 686–1,004 µs |
| 1.20.1 `level.dat` (post-F2) | 1 | 180 KB | 58 B | 36,498 B | 2,976 µs | 4,628 µs |
| opaque, non-NBT | 2 | 20 B / 12 KB | — | 29 B / 171 B | 0 µs (pass-through) | 1 µs / 12 µs |

Notes:

* The chunk timings are given as a range across three runs on the same machine; run-to-run spread
  is about ±30% (a single run is not a stable number). Byte counts are identical across runs.
* "encode" is align + backend compress for a DELTA target. It excludes the Checkpoint block-header
  and file-append cost, which the M5.5 measurements above already cover.
* The 180 KB `level.dat` is the worst observed target: 2.98 ms canonicalise plus 4.63 ms
  align/compress. The canonicalisation budget therefore uses **no `subTargetType` gating**
  (option a): a 5 ms ceiling for chunks/small files and 10 ms for `TYPE_FILE` covers every
  measured payload with headroom. The closure run's 1,196-entry baseline (1.20.1 Forge) completed
  well inside the 60 s watchdog; it was not separately instrumented.
* The `level.dat` row is post-F2: the live capture stores the written file, 179,764 bytes including
  Forge's `fml` subtree. The live vault recorded 99–100-byte deltas for this target; the offline
  fixture pair gives 58 B. F2 closed the 1.20.1 `level.dat` root-tag gap, so the earlier
  "2.7 KB captured root" caveat no longer applies to new snapshots.
* Delta sizes are dominated by dictionary quality, not by the AST: the stored old bytes are the
  whole zstd dictionary, so a 25-byte patch for a 35–71 KB chunk is the normal case, not a
  suspicious one. AST addons beat this only on containers whose palette reorders.
* The 1.20.1 `level.dat` fixture comes from the 2026-10-21 closure run (post-F2); its two versions
  are both 179,764 bytes and differ only in the fields the server rewrites on save.

Reproduce with:

```bash
./bench/codec-bench.sh zstd/1
```

The script compiles the engine into `bench/out/` (never `build/`), prints one CSV row per pair and
summary p50/p95 lines, and exits non-zero if any round trip fails. Addons pass their jar through
`BENCH_CP` and their entry class through `BENCH_CLASS`; the harness refuses to report numbers when
the codec id does not match the requested one.

## Four codec columns (same 31-pair set, level 3)

The addon jars are no longer built or shipped with this repository, so only the `zstd/1` column is
reproducible here. The other three columns are historical measurements.

```bash
./bench/codec-bench.sh zstd/1
```

Mixin is not available in a plain JVM, so addon columns call their codec entry points directly.
That disables the `TreeAligner` capture seam: **encode times include parsing both sides**, which is
the pessimistic number (the in-game path parses only the old one). Byte counts are identical either
way, and every pair round-trips in all four columns.

| Family | Metric | `zstd/1` | `hdiff-lzma2/1` | `customast-zstd/1` | `customast-lzma/1` |
| --- | --- | --- | --- | --- | --- |
| block chunk (n=12) | Δ p50 / p95 | **25 / 50 B** | 40 / 164 B | 26 / 51 B | 24 / 77 B |
| block chunk (n=12) | FULL p50 / p95 | 8,079 / 13,182 B | 6,624 / 10,600 B | 8,079 / 13,182 B | **6,572 / 10,546 B** |
| block chunk (n=12) | encode p50 / p95 | **1.74 / 3.15 ms** | 2.71 / 5.54 ms | 3.61 / 9.68 ms | 4.65 / 7.72 ms |
| small file (n=13) | Δ p50 / p95 | 20 / 359 B | 35 / 402 B | 21 / 360 B | **18 / 340 B** |
| small file (n=13) | FULL p50 / p95 | 85 / 617 B | 132 / 648 B | 85 / 617 B | **85 / 592 B** |
| `level.dat` (n=4) | Δ p50 / p95 | 43 / 58 B | 57 / 60 B | 44 / 50 B | **42 / 45 B** |
| `level.dat` (n=4) | FULL p50 / p95 | 1,576 / 36,498 B | 1,556 / 30,172 B | 1,576 / 36,498 B | **1,502 / 30,118 B** |
| opaque, non-NBT (n=2) | Δ / FULL | **29 / 171 B** | 76 / 268 B | 29 / 171 B | 34 / 213 B |
| all 29 pairs | total Δ bytes | **1,303 B** | 2,001 B | 1,322 B | 1,309 B |
| all 29 pairs | total encode | **29.8 ms** | 51.5 ms | 59.6 ms | 86.6 ms |

`total encode` is the sum of the per-target encode times in one pass over the 29 pairs — a
codec-only replay, **not** a server checkpoint: it excludes block-file IO, the 42-byte headers, the
keyframe bookkeeping and the write-back verify pass.

What the numbers say — including the parts that do not flatter the addons:

* **On this fixture set, the core `zstd/1` is the smallest total.** 1,303 B vs 1,309 B for
  `customast-lzma/1` and 1,322 B for `customast-zstd/1`. The deltas are already 20–26 B; there is
  almost nothing left for a smarter algorithm, and the AST's framing costs the 1-byte mode flag.
  The addon-era "70%+ smaller" expectation is **not observed** on these pairs and must not be
  advertised.
* **The AST path is really exercised.** Every one of the 12 chunk fixtures lifts 8–14
  `PalettedContainerTag` nodes, so palette diffing and the 4096-index XOR stream are live.
* **Where the AST wins:** `level.dat` p95 (50 B vs 58 B for zstd) and the synthetic palette-width
  jump test (62 B vs 77 B). It is a re-layout win, not a general volume win.
* **LZMA wins FULL blocks** by ~19% (6,572 vs 8,079 B per chunk; 30,118 vs 36,498 B for the big
  `level.dat`) and pays for it in encode time (4.65 vs 1.74 ms per chunk p50, 86.6 vs 29.8 ms per
  pass). Deltas do not benefit; on the 164-byte p95 chunk `hdiff-lzma2/1` is the worst column.
* **Non-NBT payloads** are pure overhead for the AST columns: `customast-lzma/1` produces 34 B
  instead of 29 B and 213 B instead of 171 B. `hdiff-lzma2/1` is worse still (76/268 B) because it
  stores an LZMA2 frame even for 20 bytes.
* **Mode selection** (PLAN 2, 29 pairs): AST `0x02` on 7 pairs, dictionary guard `0x03` on 22,
  full `0xFF` on 0. The guard is what keeps the addon from ever losing to `zstd/1`, and the
  absence of `0xFF` on real pairs is why "warn about repeated `0xFF`" stays unimplemented.

### Level curve (zstd level 3 → 9 → 19)

Same fixtures, `bench.zstdLevel` override, `customast-zstd/1` and `zstd/1`:

| Level | Codec | chunk Δ p50 / p95 | chunk FULL p50 | chunk encode p50 | all-pair total Δ |
| --- | --- | --- | --- | --- | --- |
| 3 | `zstd/1` | 25 / 50 B | 8,079 B | 1.61 ms | 1,303 B |
| 9 | `zstd/1` | 25 / 45 B | 7,190 B | 1.89 ms | 1,264 B |
| 19 | `zstd/1` | 26 / 46 B | 6,510 B | 2.94 ms | 1,313 B |
| 3 | `customast-zstd/1` | 26 / 51 B | 8,079 B | 3.82 ms | 1,322 B |
| 9 | `customast-zstd/1` | 25 / 46 B | 7,190 B | 3.85 ms | 1,293 B |
| 19 | `customast-zstd/1` | 24 / 47 B | 6,510 B | 15.2 ms | 1,311 B |

Decision (PLAN 2 §7): the schema default stays **3**. Level **9** is the recommended value in the
addon README: ~11% smaller FULL blocks and a slightly better p95 for no measurable chunk-encode cost
(3.82 → 3.85 ms p50 is noise). Level 19 buys another ~9% of FULL for a 4× encode cost (15.2 ms p50,
p95 28.8 ms) and is not a default.

### Operator marginal cost/benefit

`transpose` and `morton` are unconditional in the protocol (PLAN 2 decision 2); the flag byte only
lets the decoder read patches written with them disabled. Measured with the guard off so the
operator is not masked:

| Fixture | Transpose on | Transpose off | Morton on | Morton off |
| --- | --- | --- | --- | --- |
| all 31 real pairs | 0 B change | — | 0 B change | — |
| synthetic float-heavy (512 doubles, low mantissa only) | 47 B | **39 B** | — | — |
| synthetic 4×4×4 cluster in a 4096 palette | — | — | **41 B** | 73 B |

* `morton` is a clear win when block edits are spatially clustered (44% smaller on the synthetic
  case) and a no-op on this fixture set: there were no block edits at all between the captured
  chunk pairs.
* `transpose` is a **loss** with a zstd dictionary (-8 B here): the byte-interleaved XORs are what
  the dictionary predicts best. It was designed for LZMA2-style entropy coding (PLAN 3). It is kept
  because decision 2 made the operator set shared and unconditional; if this ever needs to change,
  the sanctioned path is deleting the operator and bumping `codecId` to `/2`, not adding a switch.

Timings above are from one run; run-to-run spread on this machine is about ±30% (byte counts are
stable). Even the slowest column at 4.65 ms p50 per chunk is ~0.47 s for a 100-chunk checkpoint,
an order of magnitude inside the 60 s watchdog.

### Modded-save closure (real Create / AE2 / Mekanism, successive saves)

Three genuine successive pairs from a dedicated 1.21.1 NeoForge server running the 248-mod
`NAST easy c1.9.4` pack (AE2 19.2.17, Mekanism 10.7.19.85, Create 6.0.10). Between the two
snapshots, Create/AE2/Mekanism blocks were placed with `/setblock`; the vault itself was written by
`customast-zstd/1`, and `bench/tools/ExtractModdedPairs.java` reconstructed the last two versions
of each target (re-checking every block's XXH64). Provenance and the exact block list are in
`bench/fixtures-modded/README.md`.

```bash
FIXTURES=bench/fixtures-modded ./bench/codec-bench.sh zstd/1
```

| Fixture | Codec | raw old / new | Δ | FULL | encode | round-trip |
| --- | --- | --- | --- | --- | --- | --- |
| `chunk_0_0`, 20 `create:` hits, the placed blocks | `zstd/1` | 61,252 / 63,534 B | 472 B | 11,804 B | 2,299 µs | ok |
| same | `customast-zstd/1` | same | **438 B** | 11,804 B | 4,092 µs | ok |
| `chunk_-1_3`, 110 KB, 11 `create:` hits | `zstd/1` | 110,016 / 110,016 B | 27 B | 20,707 B | 4,902 µs | ok |
| same | `customast-zstd/1` | same | 28 B | 20,707 B | 8,087 µs | ok |
| `chunk_-4_-2`, 54 KB, 11 `create:` hits | `zstd/1` | 54,408 / 54,408 B | 25 B | 12,926 B | 2,118 µs | ok |
| same | `customast-zstd/1` | same | 26 B | 12,926 B | 3,175 µs | ok |

* The addon **wins by 34 B (7%)** on the chunk whose blocks actually changed, and costs the usual
  +1 byte on the two pairs that only moved metadata.
* All three pairs carry Create and Mekanism block entities (11–20 `create:`, 17–28 `mekanism:`
  references); the `chunk_0_0` pair additionally carries the placed AE2 blocks (`ae2:` ×4). The
  63,534-byte new chunk is reproduced byte-for-byte, block entities included.
* No Create block entity existed in the older fixture set; this run supplies it with genuine
  successive saves rather than a field-edited copy.

## In-game four-node run (2026-10-21, historical addon run)

Each node ran a dev server with the addon jar in `mods/`, a fresh world, and the same sequence over
RCON: `/backup create` → block edits + `time set` → `/backup create` again → `/backup verify` on
both slots → `/backup restore S1 confirm` → check the edited blocks are air again.

| Node | Vault stamp | S1 (entries / physical) | S2 delta (entries / physical) | `/backup verify` | Restore |
| --- | --- | --- | --- | --- | --- |
| 1.20.1 Forge | `customast-zstd/1`, `canon=nbt1` | 1,236 / 5,287,439 B | 187 / 23,469 B | S1+S2: 618 targets, 0 failures | restart 6.8 s, 3/3 blocks reverted |
| 1.21.1 NeoForge | `customast-zstd/1`, `canon=nbt1` | 122 / 537,321 B | 17 / 658 B | S1+S2: 61 targets, 0 failures | restart 2.8 s, 2/2 blocks reverted |
| 1.20.1 Fabric | `customast-zstd/1`, `canon=nbt1` | 1,248 / 5,277,663 B | 102 / 2,490 B | S1+S2: 624 targets, 0 failures | restart ok, 2/2 blocks reverted |
| 1.21.1 Fabric | `customast-zstd/1`, `canon=nbt1` | 118 / 541,684 B | 14 / 619 B | S1+S2: 59 targets, 0 failures | restart ok, 2/2 blocks reverted |

* All four vaults were written and re-read with `customast-zstd/1`; Forge's debug log shows
  `Mixing MixinConfigValues / MixinEngineSettings / MixinNbtCanon / MixinCodec`, so the four runtime
  replacement points really applied (not merely compiled).
* The 1.20.1 Forge vault's 326 DELTA blocks split as **32 AST (`0x02`) + 283 guard (`0x03`) chunks**
  and **2 AST + 9 guard files**, with **zero `0xFF`** blocks. The AST path is used by the real
  server, not only by the unit tests.
* **Uninstall-without-rollback is loud but safe.** Removing the addon jar and starting the same
  1.20.1 Forge world leaves the server up (Done in 4.7 s) and logs
  `Backup vault codec mismatch: vault was created with 'customast-zstd/1', but current runtime uses 'zstd/1'`;
  `/backup status`, `/backup list` and `/backup create` all return that mismatch as a failure.
  This is the designed codec-mismatch behaviour, confirmed on a real vault.
* **The reverse direction also refuses.** Starting the addon on a legacy `zstd/1` vault logs
  `vault was created with 'zstd/1', but current runtime uses 'customast-zstd/1'` and disables the
  backup runtime; the server keeps running. (The run hit a port bind conflict from the previous
  server still shutting down, so `/backup status` was not re-queried, but the mismatch is the same
  code path as the tested direction.)
* The test worlds and vaults lived under `run/`; they are local-only and are not committed.

### Real checkpoint wall clock

Measured on the 1.20.1 Forge node through RCON, timing the command round trip (the server thread
does all the work, so this is the checkpoint wall clock, not network latency):

| Command | Work | Wall clock |
| --- | --- | --- |
| `/backup create` | 2,134 entries, 39.5 MB logical, 1.98 MB physical | **2.61 s** |
| `/backup verify S1` | 2,126 targets (2,124 present, 2 absent) | **0.09 s** |
| restore (armed → server ready) | 618 targets, restart included | 6.8 s |

The 2.61 s checkpoint is ~1.2 ms per entry on average, an order of magnitude inside the 60 s
watchdog.

The three modded pairs in `bench/fixtures-modded` come from a real 248-mod server (Create, AE2,
Mekanism) and cover the "gzip-compressed NBT inside a `byte[]`" risk; an entire modded vault is
not committed. Non-NBT `0xFF` remains an offline-only case: the real vault had zero `0xFF` blocks.
