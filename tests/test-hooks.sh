#!/usr/bin/env bash
# Hook-side tests: compile the platform-neutral mod code against a real Minecraft artifact and
# exercise COW, read capture, the restore gate and the write-path coverage table outside the game.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

find_jar() {
    local group="$1" pattern="$2"
    find "$HOME/.gradle/caches/modules-2/files-2.1/$group" -name "$pattern" ! -name '*sources*' \
        2>/dev/null | sort | tail -n 1 || true
}

MC_JAR="${MC_JAR:-}"
if [ -z "$MC_JAR" ]; then
    MC_JAR="$(find "$ROOT/versions" -path '*/build/moddev/artifacts/neoforge-*.jar' ! -name '*-sources.jar' 2>/dev/null | sort | tail -n 1 || true)"
fi
if [ -z "$MC_JAR" ]; then
    MC_JAR="$(find "$ROOT/versions" -path '*/build/moddev/artifacts/forge-*.jar' ! -name '*-sources.jar' 2>/dev/null | sort | tail -n 1 || true)"
fi
if [ -z "$MC_JAR" ] || [ ! -f "$MC_JAR" ]; then
    echo "no Minecraft artifact found; run ./gradlew :1.21.1-neoforge:createMinecraftArtifacts" >&2
    exit 1
fi

ZSTD_JAR="${ZSTD_JAR:-$(find_jar com.github.luben 'zstd-jni-*.jar')}"
GUAVA_JAR="${GUAVA_JAR:-$(find_jar com.google.guava 'guava-*.jar')}"
FASTUTIL_JAR="${FASTUTIL_JAR:-$(find_jar it.unimi.dsi 'fastutil-*.jar')}"
DFU_JAR="${DFU_JAR:-$(find_jar com.mojang 'datafixerupper-*.jar')}"
BRIGADIER_JAR="${BRIGADIER_JAR:-$(find_jar com.mojang 'brigadier-*.jar')}"
LOG4J_JAR="${LOG4J_JAR:-$(find_jar org.apache.logging.log4j 'log4j-api-*.jar')}"
MIXIN_JAR="${MIXIN_JAR:-$(find "$HOME/.gradle/caches/modules-2/files-2.1" -name 'sponge-mixin-*.jar' \
    ! -name '*sources*' ! -name '*javadoc*' 2>/dev/null | sort | tail -n 1 || true)}"
ANNOTATIONS_JAR="${ANNOTATIONS_JAR:-$(find_jar org.jetbrains 'annotations-*.jar')}"
AUTHLIB_JAR="${AUTHLIB_JAR:-$(find_jar com.mojang 'authlib-*.jar')}"
# The shared tree also holds the platform mixins (ConnectionAccess, UtilMixin) and RestoreHold,
# which needs netty because Connection extends a netty handler.
NETTY_CP="$(find "$HOME/.gradle/caches/modules-2/files-2.1/io.netty" -name 'netty-*.jar' \
    ! -name '*sources*' ! -name '*javadoc*' 2>/dev/null | sort | tr '\n' ':' || true)"

for jar in "$ZSTD_JAR" "$GUAVA_JAR" "$FASTUTIL_JAR" "$DFU_JAR" "$BRIGADIER_JAR" "$LOG4J_JAR" "$MIXIN_JAR" "$ANNOTATIONS_JAR" "$AUTHLIB_JAR"; do
    if [ -z "$jar" ] || [ ! -f "$jar" ]; then
        echo "missing dependency jar (set the matching *_JAR variable)" >&2
        exit 1
    fi
done

CP="$MC_JAR:$ZSTD_JAR:$GUAVA_JAR:$FASTUTIL_JAR:$DFU_JAR:$BRIGADIER_JAR:$LOG4J_JAR:$MIXIN_JAR:$ANNOTATIONS_JAR:$AUTHLIB_JAR:$NETTY_CP"
MAIN_OUT="$ROOT/build/hook-classes"
TEST_OUT="$ROOT/build/hook-test-classes"
rm -rf "$MAIN_OUT" "$TEST_OUT"
mkdir -p "$MAIN_OUT" "$TEST_OUT"

mapfile -t MAIN_SRC < <(find "$ROOT/src/main/java" -name '*.java' ! -path '*/client/*' | sort)
mapfile -t TEST_SRC < <(find "$ROOT/src/test-hooks/java" -name '*.java' | sort)

echo "[H] javac --release 21 (main + hook tests)"
javac --release 21 -Xlint:all,-classfile -Werror -cp "$CP" -d "$MAIN_OUT" "${MAIN_SRC[@]}"
javac --release 21 -Xlint:all,-classfile -Werror -cp "$CP:$MAIN_OUT" -d "$TEST_OUT" "${TEST_SRC[@]}"

echo "[H3-H6] running hook tests against $(basename "$MC_JAR")"
# No Log4j2 provider is on this classpath; silence the API's "could not find a provider" status line.
java -Dlog4j2.statusLoggerLevel=OFF -cp "$CP:$MAIN_OUT:$TEST_OUT" \
    io.github.linkfgfgui.minimalbackup.server.HookTestMain
