#!/usr/bin/env bash
# NodeParityTest at the source level: every capture mixin must exist on all four nodes and expose
# the same logical hooks. Version-specific method names are normalised, so 1.20.1's
# saveDataTag/readLevelData and 1.21.1's saveLevelData/readLevelDataTagRaw compare equal.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

NODES=(1.20.1-forge 1.20.1-fabric 1.21.1-neoforge 1.21.1-fabric)
MIXIN_DIR="src/main/java/io/github/linkfgfgui/minimalbackup/mixin"

# Capture mixin -> logical hooks that must be present on every node.
declare -A REQUIRED=(
    [DimensionDataStorageMixin]="readSavedData"
    [IOWorkerMixin]="loadAsync scanChunk"
    [LevelStorageAccessMixin]="save"
    [LevelStorageSourceMixin]="read"
    [PlayerDataStorageMixin]="load save"
    [RegionFileStorageMixin]="write read"
    [SavedDataMixin]="save"
)

normalise() {
    case "$1" in
        saveDataTag|saveLevelData) echo save ;;
        readLevelData|readLevelDataTagRaw) echo read ;;
        *) echo "$1" ;;
    esac
}

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

for mixin in "${!REQUIRED[@]}"; do
    expected="$(printf '%s\n' ${REQUIRED[$mixin]} | sort -u)"
    for node in "${NODES[@]}"; do
        file="versions/$node/$MIXIN_DIR/$mixin.java"
        if [ ! -f "$file" ]; then
            file="$MIXIN_DIR/$mixin.java"
        fi
        [ -f "$file" ] || fail "missing $file"
        actual="$(
            grep -vE '^[[:space:]]*(//|\*)' "$file" \
                | grep -oE '@Inject\(method = "[^"]+"' \
                | sed -E 's/.*method = "([^"(]+).*/\1/' \
                | while read -r name; do normalise "$name"; done \
                | sort -u
        )"
        if [ "$actual" != "$expected" ]; then
            missing="$(comm -23 <(printf '%s\n' "$expected") <(printf '%s\n' "$actual") | tr '\n' ' ')"
            extra="$(comm -13 <(printf '%s\n' "$expected") <(printf '%s\n' "$actual") | tr '\n' ' ')"
            fail "$node/$mixin.java hooks [$(echo $actual)]; missing [${missing% }] extra [${extra% }]"
        fi
    done
done

echo "NODE PARITY OK (${#NODES[@]} nodes, ${#REQUIRED[@]} capture mixins)"
