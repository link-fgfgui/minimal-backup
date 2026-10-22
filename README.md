# Minimal Backup

A lightweight Minecraft backup mod that saves **per-chunk/file Zstd deltas** instead of copying whole world folders, and restores worlds via **in-memory read interception** and **hot server restarts**.

| Minecraft | Fabric | Forge | NeoForge |
| :--- | :---: | :---: | :---: |
| **1.20.1** | ✅ | ✅ | — |
| **1.21.1** | ✅ | — | ✅ |

> *Architecture details can be found in `docs/DESIGN_NOTES.md`.*

**Runtime dependencies:** Fabric builds require **ForgeConfigAPIPort**; Forge and NeoForge use
their built-in config systems. **UILib + Architectury** are optional client-side and are not
needed on dedicated servers.

## Key Features

* **Delta Storage (Space-First):** Tracks individual chunks and `.dat` files in append-only `.zpack` archives. The first block is a full copy; subsequent blocks are Zstd dictionary deltas. Untouched chunks take zero space.
* **Fast, Copy-Free Restores:** Restoring does not unzip files beforehand. It intercepts game reads to serve snapshot data directly, falling through to disk for untouched data.
* **In-Process Server Swap:** On dedicated servers, restoring boots a fresh server instance in the same JVM while holding player connections open with a heartbeat. Modded clients reconnect automatically. Integrated (single-player) servers use the same connection hold while the old server stops, then the client quietly reloads the world, so the vanilla "connection lost" screen never appears.
* **Server-Side & Optional UI:** Vanilla clients can join freely. Modded clients can press **`F9`** (or `/backup ui` in single-player) for the visual **Time Tree**; it needs **UILib** + **Architectury** (`0.3.6` + `9.2.14` on 1.20.1, `9.0.0` + `13.0.6` on 1.21.1). Without them the client shows a chat hint and `/backup` commands still work. Players below permission level 2 get a read-only tree: list and status are available, while create/restore/rename/delete/verify are disabled and the server rejects those requests. A second key binding, **unbound by default**, creates a snapshot immediately using the default timestamp title; bind it in the vanilla controls screen if you want one-key backups.

## Commands (Requires OP Level 2)

```text
/backup create [name]            # Create a manual snapshot
/backup list                     # List snapshots
/backup status                   # Show runtime & vault status
/backup restore <slot> [confirm] # Checkpoint current state & restore to <slot>
/backup disarm                   # Cancel a pending restore
/backup delete <slot>            # Soft-delete (tombstone) a snapshot
/backup rename <slot> <name>     # Rename a snapshot
/backup verify <slot>            # Verify block hashes for a snapshot
/backup gc [confirm]             # Preview or run garbage collection (compaction)
/backup baseline [confirm]       # Import all existing chunks/data into the vault
/backup reload                   # Reload server config
/backup ui                       # Open client GUI
```

## Storage & Configuration

* **Vault Path:** `<gameDir>/mbackups/<worldFolderName>/` (Stored outside `saves/`).
  * *Note:* Deleting a world keeps its backups. Renaming a world folder starts a fresh backup vault.
* **Configs:** `config/minimalbackup-server.toml` and `config/minimalbackup-client.toml`.
  * **Triggers:** Auto-backup on world unload (`triggerOnUnload=true`) or timer (`triggerOnInterval` + `intervalMinutes`).
  * **Retention & Compression:** Configure `retentionKeep`, `retentionMaxBytes`, and `zstdLevel`.
  * **Scope:** Enable `fullBaseline` to import untouched worlds, or `copyJson` to include `advancements/` and `stats/`.

## Limitations (Non-Goals)

* **NBT Pipeline Only:** `datapacks/`, `serverconfig/`, and custom non-NBT mod databases are ignored. (`advancements/*.json` and `stats/*.json` require `backup.copyJson=true`).
* **Local & Version-Locked:** Backups are local only (`<gameDir>/mbackups`) and cannot be restored across different Minecraft versions.
* **Vanilla Client Reconnect:** Vanilla clients can play normally, but must manually click "Reconnect" after a dedicated server restore.

## Offline CLI Tool

The mod jar can be run directly with Java (while the world is stopped) for offline maintenance:

```bash
java -cp <mod.jar> engine.cli.Main list     <gameDir> <worldKey>              # List snapshots & sizes
java -cp <mod.jar> engine.cli.Main rollback <gameDir> <worldKey> <slot> <dir> # Rebuild world into <dir>
java -cp <mod.jar> engine.cli.Main compact  <gameDir> <worldKey>              # Run garbage collection
java -cp <mod.jar> engine.cli.Main heal     <gameDir> <worldKey>              # Clean up interrupted writes
```

## Building & Publishing

`zstd-jni` is bundled automatically; players do not need to install it.

```bash
# Build (replace node with 1.20.1-fabric, 1.20.1-forge, 1.21.1-fabric, or 1.21.1-neoforge)
./gradlew :1.21.1-neoforge:buildAndCollect   # Output: build/libs/<version>/

# Publish to Modrinth / CurseForge (requires MODRINTH_TOKEN / CURSEFORGE_TOKEN)
./publish.sh --dry-run
./publish.sh
```

Pushing a `v*` tag runs `.github/workflows/release.yml`: it checks that the tag equals
`v` + `mod.version`, builds all four nodes, runs the test suites and uploads the jars to
CurseForge. The workflow reads the `CURSEFORGE_TOKEN` secret and the
`CURSEFORGE_PROJECT_ID` repository variable.

## Codec addons

The engine ships `zstd/1`. A codec addon implements
`io.github.linkfgfgui.minimalbackup.engine.api.CodecProvider` — `codecId()`, `compressFull`,
`compressDelta` and `decompress` — and calls `CodecRegistry.register(...)` from its loader
entrypoint. No mixin into core is required. The active id is stamped into `index.properties`;
**vaults are never interchangeable between codecs**, and a vault written by an addon refuses to
load once the addon is removed. Roll back (or copy the world out) before uninstalling, and install
at most one codec addon.

A complete provider looks like this (`MyBackend` stands for the addon's codec):

```java
import io.github.linkfgfgui.minimalbackup.engine.api.CodecProvider;
import io.github.linkfgfgui.minimalbackup.engine.api.CodecRegistry;
import java.io.IOException;

public final class MyCodec implements CodecProvider {

    @Override
    public String codecId() {
        return "mycodec/1";
    }

    @Override
    public byte[] compressFull(byte[] raw, int level, int subTargetType) throws IOException {
        return MyBackend.compressFull(raw, level);
    }

    @Override
    public byte[] compressDelta(byte[] target, byte[] dict, int level, int subTargetType)
            throws IOException {
        return MyBackend.compressDelta(target, dict, level);
    }

    @Override
    public byte[] decompress(byte[] patch, byte[] dict, int originalLength, int subTargetType)
            throws IOException {
        return MyBackend.decompress(patch, dict, originalLength);
    }
}
```

Register it once from the loader entrypoint, before the first world or vault is opened:

```java
CodecRegistry.register(new MyCodec());
```

The engine calls a six-argument `compressDelta` overload that also carries long-distance
matching hints (`longMode`, `longWindowLog`). `CodecProvider` provides a default implementation
that ignores the hints and forwards to the four-argument method, so the example above is complete;
override it only if your backend supports long mode.

The contract is exact byte round-trip: `decompress(compressFull(raw))` and
`decompress(compressDelta(target, dict), dict)` must return the original bytes. `dict` is
`null` for FULL blocks; `subTargetType` is `0` for a whole file and `1` for a chunk. Do not
retain the input arrays. `codecId` is stamped into `index.properties`, so changing it later makes
existing vaults unreadable; only one non-default id can be registered at a time.

The prototype addons (`hdiff-lzma2/1`, `customast-zstd/1`, `customast-lzma/1`) are not part of
this repository and are no longer maintained; their measurements are kept only as history in
`docs/BENCH.md`. The `CodecProvider` API is the supported integration point.

## Tests

```bash
./build.sh                       # engine + node parity: javac -Werror + E0-E15 + H0/H1
./tests/test-node-parity.sh      # every capture mixin exists on all four nodes with the same hooks
./tests/test-package.sh          # packaged jars: Forge refmap, Fabric loader floors, mixin configs
./tests/test-hooks.sh            # hook layer against a real Minecraft artifact: H3-H9
./tests/test-config-parity.sh    # all four spec builders come from ConfigSchema
```

All test scripts live in `tests/`; see `tests/README.md` for prerequisites.
`tests/test-hooks.sh` needs a Minecraft artifact produced by Gradle
(`./gradlew :1.21.1-neoforge:createMinecraftArtifacts`) and the dependency jars in the Gradle
cache.

## License

GNU Lesser General Public License v3.0 only (`LGPL-3.0-only`). See `LICENSE`.
