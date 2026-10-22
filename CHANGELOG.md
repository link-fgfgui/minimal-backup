# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Changed

- Architectury API is no longer declared as a prerequisite; UILib is the only optional client
  dependency (future UILib versions drop Architectury).

## [0.1.0] - 2026-10-22

Initial release. Targets 1.20.1 Forge/Fabric and 1.21.1 NeoForge/Fabric.

### Added

- Per-target `.zpack` vaults: append-only block chains with a FULL first block, zstd
  dictionary deltas for later blocks, optional keyframes, tombstones, retention, per-file
  compaction and startup self-healing.
- Write capture for chunks, `SavedData`, player data and `level.dat`; read capture plus
  write-time COW for the first value of a target; optional `backup.fullBaseline` import.
- Sparse-read restore: only keys covered by the snapshot are served, everything else passes
  through to disk; crash marker, pre-restore checkpoint and optional `restore.forceDirty`.
- Dedicated-server in-process instance replacement on all four nodes: connection hold with an
  8-second heartbeat and discard listener, replacement server in the same JVM on the same port,
  ready packet and automatic reconnect for modded clients. Integrated servers hold the local
  connection the same way while the old server stops, then the client closes it quietly and
  reopens the world, so no "connection lost" screen is shown.
- Optional JSON archiving (`backup.copyJson`, default off) for world-level `*/*.json`
  (advancements, stats) as whole-file zstd copies without diff; the first snapshot also archives
  `icon.png` at the vault root. Neither participates in the NBT delta chain or restore.
- `/backup` commands: `create`, `list`, `status`, `reload`, `restore` (with `confirm`),
  `disarm`, `delete`, `rename`, `verify`, `gc` (with `confirm`), `baseline` (with
  `confirm`) and `ui`.
- Optional UILib time tree on all four nodes (UILib 0.3.6 + Architectury 9.2.14 on 1.20.1,
  UILib 9.0.0 + Architectury 13.0.6 on 1.21.1) with create/rename/verify/delete/restore/GC
  actions. The vanilla list screen was removed; the tree's Config button edits the `ui.*`
  options, and the screen talks to the server through `UiBridge`. Below permission level 2 the
  tree is read-only (list/status); create/restore/rename/delete/verify are disabled in the client
  and rejected by the server. An unbound-by-default key binding creates a snapshot with the
  default timestamp title without opening the tree.
- Offline CLI: `list`, `rollback` (rebuilds `region/`, `entities/`, `poi/` and
  custom-dimension `.mca` files from per-chunk blocks), `compact` and `heal`.
- Stable codec API: `engine/api/CodecProvider` (`codecId`, `compressFull`,
  `compressDelta`, `decompress`) plus `CodecRegistry`; the built-in provider is
  `zstd/1`.
- Shared `ConfigSchema` for all four loaders: triggers, retention, zstd level/long mode,
  keyframe interval, `captureOnRead`, `fullBaseline`, `copyJson`, `verifyAfterWrite` and
  restore options.
- Benchmark harness (`bench/codec-bench.sh`) and real chunk/file fixtures under
  `bench/fixtures`; modded fixtures under `bench/fixtures-modded`.

### Changed

- Checkpoints skip no-op writes: a non-ABSENT entry whose aligned bytes equal the previous
  visible block appends no block, and a repeated ABSENT entry appends no tombstone. The previous
  block stays the target's head, so the new snapshot resolves to the same value.
- The `.zpack` index cache stores only each block header's offset (first absolute, then varint
  deltas, about 2 B per block). A missing or corrupt cache falls back to a sequential scan.
- NBT payloads are canonicalised before hashing and storage: compound keys are sorted, palette
  containers are lifted only when the round trip is byte-exact, and delta targets are aligned to
  the previous **stored** version. `Timeline` stamps `canon=nbt1`; old vaults without the field
  still load.
- 1.20.1 `level.dat` capture now records the old tag at HEAD and reads the written file back at
  RETURN, preserving Forge's `fml` subtree and any other root tags added by mods.
- Player-data write capture is present on all four nodes.
- The codec addon surface is the four-method `CodecProvider` API, registered through
  `CodecRegistry`, instead of overwriting core methods with mixins. The prototype addons are no
  longer part of this repository and are archived outside it.
- Test scripts are consolidated under `tests/`; the engine suite remains `./build.sh`.
- Loader-identical sources moved to the shared `src/main` tree: `FriendlyCodec` and the
  `IOWorkerMixin`, `RegionFileStorageAccess` and `RegionFileStorageMixin` capture mixins.
  Duplicated vault walks, chunk packing, world-key validation and command identity formatting now
  reuse the existing helpers.
- License: LGPL-3.0-only. `LICENSE` contains the LGPLv3 text and `LICENSE.GPL-3.0` contains
  the GPLv3 text it incorporates.
- Default zstd compression level is now 9 (was 3).
- Engine settings are an immutable `EngineSettings` value built by `ConfigValues` and passed
  into `Checkpoint`, `BlockFile.append` and `Codec.compressDelta`; the engine keeps no static
  defaults or setters. `CodecProvider` gained a default six-argument `compressDelta` overload
  for long-mode hints, so existing four-method providers keep working.
- `Checkpoint.SnapshotInfo.source` no longer defaults to `manual`; callers that need that
  label pass it explicitly (as `BackupService` already did).
- Default values live only in `ConfigSchema`: the change-set buffer takes its limits from config,
  legacy vaults without a codec field use `CodecRegistry.DEFAULT_CODEC_ID`, and the client
  keybind falls back to the schema default instead of a second F9 literal.

### Removed

- The CLI `checkpoint` command and restore support for its whole-file `.mca`/`.mcc` snapshot
  format. Per-chunk capture and the offline `rollback` writer supersede it.
- Unused config options `backup.nonNbtPolicy`, `restore.keepPlayerPositions`,
  `restore.timeoutSeconds` and `debug.dumpCoverStats`; none had a reader.
- `ui.keybind` and the key field in the mod config screen; the time-tree key is now rebound
  only through Minecraft's `Options -> Controls` screen, so the two paths cannot disagree.
- `ui.animations`, which was exposed in the config screen but had no behaviour.
- The unimplemented `/backup list [page]` argument; `/backup list` now matches the command surface.
- Unused internal helpers `ConfigValues.raw/snapshot/string`, `MinimalBackup.isInitialised/backupRoot`,
  `BackupRuntime.coverPass/worldChangedOutside`, `EngineSettings.codecApiVersion` and
  `Timeline.lastRollbackAt`.
- The `STRING` config value type; every remaining option is a boolean, integer or long.

### Fixed

- 1.20.1 Forge and 1.21.1 Fabric never captured player-data writes because the
  `PlayerDataStorage.save(Player)` hook was missing.
- 1.20.1 snapshots lost root-level `level.dat` siblings (notably Forge's `fml`), which also
  polluted delta sizes and skipped Forge's mod-mismatch data on restore.
- Forge production mixins now name the generated refmap; packaged jars include
  `minimalbackup.refmap.json`.
- Fabric loader minimums are per node, so the 1.20.1 jars no longer require a 1.21.1-era loader.
- 1.20.1 Forge clients no longer stop at `LoadingErrorScreen` when `pack.mcmeta` is missing.
- `TYPE_FILE` and `TYPE_CHUNK` blocks no longer collide at sub-target 0 (chunk `(0,0)`).
- A successful restore now makes the restored slot the timeline head, so the next snapshot
  branches from it.
- Pre-restore queue draining runs before arming and outside the capture lock, avoiding the
  save/capture deadlock.
- Fabric config reload now marks the config loaded only after at least one value was read,
  matching the Forge and NeoForge spec builders.

### Notes

- CurseForge publishing runs from `.github/workflows/release.yml` on `v*` tags; the Modrinth
  project id and token are still pending.
- Non-NBT data is skipped by default. `backup.copyJson` covers world-level advancements/stats
  only; `serverconfig/`, `datapacks/` and mod-specific stores are out of scope.
- Restore is a sparse overlay: keys absent from the snapshot pass through to the current disk.
