#!/usr/bin/env bash
# E0: compile the pure-Java engine with zero lint warnings and run the test suite.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ZSTD_JAR="${ZSTD_JAR:-}"
if [ -z "$ZSTD_JAR" ]; then
    ZSTD_JAR="$(find "$HOME/.gradle/caches/modules-2/files-2.1/com.github.luben/zstd-jni" \
        -name 'zstd-jni-*.jar' ! -name '*-sources.jar' 2>/dev/null | sort | tail -n 1 || true)"
fi
if [ -z "$ZSTD_JAR" ] || [ ! -f "$ZSTD_JAR" ]; then
    echo "zstd-jni jar not found; set ZSTD_JAR=/path/to/zstd-jni.jar" >&2
    exit 1
fi

MAIN_OUT="$ROOT/build/classes"
TEST_OUT="$ROOT/build/test-classes"
rm -rf "$ROOT/build"
mkdir -p "$MAIN_OUT" "$TEST_OUT"

# The engine and the platform-neutral parts of the server package compile without Minecraft.
# Loader-facing server classes (NbtCodec, BackupRuntime, BackupHooks, BackupCommand) are built by
# tests/test-hooks.sh against a real Minecraft artifact.
mapfile -t MAIN_SRC < <({
    find "$ROOT/src/main/java/io/github/linkfgfgui/minimalbackup/engine" -name '*.java'
    find "$ROOT/src/main/java/io/github/linkfgfgui/minimalbackup/config" -name '*.java'
    find "$ROOT/src/main/java/io/github/linkfgfgui/minimalbackup/ui" -name '*.java'
    echo "$ROOT/src/main/java/io/github/linkfgfgui/minimalbackup/server/TargetKey.java"
    echo "$ROOT/src/main/java/io/github/linkfgfgui/minimalbackup/server/ChangeSetBuffer.java"
    echo "$ROOT/src/main/java/io/github/linkfgfgui/minimalbackup/server/SnapshotKeys.java"
} | sort)
mapfile -t TEST_SRC < <(find "$ROOT/src/test/java" -name '*.java' | sort)

echo "[E0] javac --release 17 -Xlint:all -Werror (main)"
javac --release 17 -Xlint:all -Werror -cp "$ZSTD_JAR" -d "$MAIN_OUT" "${MAIN_SRC[@]}"

if [ "${#TEST_SRC[@]}" -gt 0 ]; then
    echo "[E0] javac --release 17 -Xlint:all -Werror (test)"
    javac --release 17 -Xlint:all -Werror -cp "$ZSTD_JAR:$MAIN_OUT" -d "$TEST_OUT" "${TEST_SRC[@]}"
    echo "[E0-E15] running tests"
    java -cp "$ZSTD_JAR:$MAIN_OUT:$TEST_OUT" io.github.linkfgfgui.minimalbackup.engine.TestMain
fi

if grep -RInE 'net\.(minecraft|fabricmc|neoforged|minecraftforge)' \
        "$ROOT/src/main/java/io/github/linkfgfgui/minimalbackup/engine" >/dev/null 2>&1; then
    echo "engine must not reference Minecraft/loader packages" >&2
    exit 1
fi

echo "[N1] four-node injection parity"
"$ROOT/tests/test-node-parity.sh"

echo "build ok"
