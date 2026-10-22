### Core Architecture

#### Strict 3-Layer Codebase
To keep the code maintainable and testable, it is strictly divided into three layers:
* **Engine (`engine/`)**: Pure Java. **Zero Minecraft dependencies** (enforced by the build script). Handles compression, delta math, and file I/O.
* **Server/Runtime (`server/`, `config/`, `ui/`)**: Uses Minecraft classes, but is loader-agnostic. Handles commands, runtime state, and UI data.
* **Platform (`versions/<node>/`)**: Thin adapters for Fabric, Forge, and NeoForge (entry points, network packets, and version-specific Mixins).

#### How Data is Stored (The Storage Engine)
* **Location**: Stored outside the save folder in `<gameDir>/mbackups/<worldName>/`.
* **Granular Targets**: Instead of zipping the whole world, it tracks individual chunks and individual `.dat` files (e.g., player data, `level.dat`). Each target gets its own append-only `.zpack` file.
* **Sparse & Incremental**:
  * **Sparse**: If a chunk is never touched by the game, it is never backed up.
  * **NBT Alignment + Zstd Deltas**: When saving a new version of a chunk, it aligns the NBT tree and uses the previous version as a Zstd compression dictionary—storing **only the byte differences (deltas)**, verified by XXH64 hashes.
* **Git-like Timeline**: Backups are "Snapshots." If you restore to an older snapshot and keep playing, the timeline **branches**.
* **Crash-Safe**: `index.properties` is replaced atomically and acts as the **only commit point**. If the server crashes mid-backup, the uncommitted data blocks are simply ignored and cleaned up later by Garbage Collection (`gc`).

#### How Data is Captured (Zero-Lag Interception)
The mod uses **7 Mixin hooks** to intercept Minecraft's native read/write pipelines (chunks, entities, POI, `SavedData`, player data, and `level.dat`):
* **Read-Capture & Copy-on-Write (COW)**: Before Minecraft overwrites a chunk or file for the first time, the mod captures its **original state** (Block 1) and its **new state** into an in-memory buffer (`ChangeSetBuffer`).
* **Batched I/O**: Normal gameplay writes never hit the backup disk immediately. Changes sit in RAM and are only compressed and flushed to disk during a **Checkpoint** (triggered by a timer, world unload, manual command, or when the RAM buffer hits 64 MB).

#### How Restores Work (Lazy Serving + Hot Server Swap)
Restoring a backup does **not** unzip files back into the world folder before starting:
* **Intercepting Reads**: When a restore is armed, the mod intercepts Minecraft's disk reads. If the requested chunk/file is in the snapshot, the mod serves the snapshot data directly into memory; if not, it lets Minecraft read the untouched file from the disk. Once loaded, it forces a standard world save (`saveEverything`) to make the restored state permanent on disk.
* **Dedicated Server "Hot Swap"**: On multiplayer servers, restoring doesn't shut down the JVM process or kick players. A dummy packet listener **holds player connections open and sends keep-alive heartbeats** while the mod spins down the old `DedicatedServer` instance and boots a fresh one in the background. Players are seamlessly reconnected once the new world is ready. On single-player the same hold keeps the local memory connection alive while the integrated server stops; the client then closes it quietly and reopens the world, avoiding the vanilla disconnect screen.

#### UI, Commands, and Offline CLI
* **Commands**: Controlled via `/backup` (`create`, `restore`, `list`, `verify`, `delete`, `gc`, `baseline`, etc.). Requires OP permission (Level 2).
* **GUI**: Opened via `F9` or `/backup ui` (single-player). The UILib node-based **TimeTree screen** is the only client UI and is available on all four nodes (UILib 0.3.6 / Architectury 9.2.14 on 1.20.1, UILib 9.0.0 / Architectury 13.0.6 on 1.21.1); without UILib the client shows a chat hint and commands still work. The tree is read-only below permission level 2 (list/status); create/restore/rename/delete/verify are disabled client-side and re-checked by the server. A second key binding (unbound by default) creates a snapshot with the default timestamp title without opening the tree. The config screen opens from the tree's Config button.
* **Standalone CLI**: Because the engine is pure Java, the mod Jar can be run from the command line outside of Minecraft to inspect, compact, heal, or roll back worlds offline.

#### Deliberate Limitations (What it *Doesn't* Do)
* **External Edits**: If you edit the world with the mod disabled or with an external tool, the mod detects it (via a `level.dat` SHA-256 hash check) and logs a warning, but won't re-scan the whole world to fix the gap.
* **Out-of-Scope Files**: It only hooks the NBT pipeline (plus optional whole-file copies for `advancements/*.json` and `stats/*.json`). It ignores `datapacks/`, `serverconfig/`, and custom non-NBT files created by other mods.

## Licensing

- Project license: **LGPL-3.0-only**. `LICENSE` carries the LGPLv3 text.
- Third-party dependencies: zstd-jni (BSD-2-Clause) is embedded; UILib (Apache-2.0), Architectury API (LGPL-3.0-only) and ForgeConfigAPIPort (MPL-2.0) are optional runtime dependencies and are not embedded. Keep their notices when redistributing.

## Future Work (v1+)

Not part of v0:

- Restore entry from the world list / offline snapshot selection.
- 26.x port.
