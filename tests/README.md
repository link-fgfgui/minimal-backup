# Tests

Shell-level checks live here. All scripts resolve the repository root themselves, so they can be
run from any directory.

| Script | Checks | Prerequisites |
| --- | --- | --- |
| `test-config-parity.sh` | All four loader spec builders come from `ConfigSchema`; no hard-coded keys | none |
| `test-node-parity.sh` | Every capture mixin exists on all four nodes with the same logical hooks | none |
| `test-package.sh` | Packaged jars: Forge refmap wiring, Fabric loader floors, mixin configs | built jars under `build/libs/` |
| `test-hooks.sh` | COW, read capture, restore gate, hook coverage against a real Minecraft artifact (H3-H8) | `createMinecraftArtifacts` output + Gradle cache |

The pure-Java engine suite (E0-E15, H0-H2) is compiled and run by `../build.sh`, which also calls
`test-node-parity.sh`.

```bash
./build.sh
./tests/test-config-parity.sh
./tests/test-node-parity.sh
./tests/test-package.sh
./tests/test-hooks.sh
```
