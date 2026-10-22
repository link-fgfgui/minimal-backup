#!/usr/bin/env bash
# ConfigParityTest at the source level: all four platform spec builders must come from
# ConfigSchema; a literal key in a builder would let the nodes drift.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

SPECS=(
    versions/1.20.1-forge/src/main/java/io/github/linkfgfgui/minimalbackup/config/ForgeConfig.java
    versions/1.20.1-fabric/src/main/java/io/github/linkfgfgui/minimalbackup/config/ForgeConfig.java
    versions/1.21.1-neoforge/src/main/java/io/github/linkfgfgui/minimalbackup/config/NeoForgeConfig.java
    versions/1.21.1-fabric/src/main/java/io/github/linkfgfgui/minimalbackup/config/NeoForgeConfig.java
)

for spec in "${SPECS[@]}"; do
    [ -f "$spec" ] || fail "missing spec builder: $spec"
    grep -q 'ConfigSchema.options(side)' "$spec" \
        || fail "$spec does not build from ConfigSchema.options(side)"
    if grep -Eq 'builder\.define(InRange)?\("[a-z]' "$spec"; then
        fail "$spec contains a hard-coded config key"
    fi
done

echo "CONFIG PARITY OK (${#SPECS[@]} nodes)"
