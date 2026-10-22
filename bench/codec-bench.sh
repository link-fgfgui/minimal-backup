#!/usr/bin/env bash
# Horizontal codec benchmark (originally PLAN 1 §4): per-pair patch bytes + per-target encode time.
#
#   ./bench/codec-bench.sh [codec-id]
#
# The core jar only ships zstd/1. Any other id needs the addon jar (plus its mixin launcher)
# on BENCH_CP; the harness refuses to report numbers for a codec the runtime is not running.
#
# Fixtures live in bench/fixtures/<node>/ as real *.old.nbt / *.new.nbt pairs reconstructed from
# a vault (provenance: bench/fixtures/README.md). Opaque files (*.mca, fake-level.dat) are
# reported too; they exercise the non-NBT pass-through where canonicalisation must be a no-op.
#
# Output: one CSV row per pair (full/delta bytes, canonical sizes, per-target µs, round-trip),
# then summary lines with p50/p95. Compiles into build/bench-classes and never touches
# build/, so it cannot disturb a Gradle build or be deleted by one.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CODEC_ID="${1:-zstd/1}"
FIXTURES="${FIXTURES:-$ROOT/bench/fixtures}"
OUT="$ROOT/bench/out"

ZSTD_JAR="${ZSTD_JAR:-}"
if [ -z "$ZSTD_JAR" ]; then
    ZSTD_JAR="$(find "$HOME/.gradle/caches/modules-2/files-2.1/com.github.luben/zstd-jni" \
        -name 'zstd-jni-*.jar' ! -name '*-sources.jar' 2>/dev/null | sort | tail -n 1 || true)"
fi
if [ -z "$ZSTD_JAR" ] || [ ! -f "$ZSTD_JAR" ]; then
    echo "zstd-jni jar not found; set ZSTD_JAR=/path/to/zstd-jni.jar" >&2
    exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT"

mapfile -t ENGINE_SRC < <(find "$ROOT/src/main/java/io/github/linkfgfgui/minimalbackup/engine" \
    -name '*.java' | sort)
echo "[bench] compile engine -> build/bench-classes"
javac --release 17 -cp "$ZSTD_JAR" -d "$OUT" "${ENGINE_SRC[@]}"
javac --release 17 -cp "$ZSTD_JAR:$OUT" -d "$OUT" "$ROOT/bench/src/CodecBench.java"
# Adapters are compiled per run: each one needs its own addon jar on the classpath.
if [ -n "${BENCH_CP:-}" ] && [ "${BENCH_CLASS:-}" = "HdiffBenchCodec" ]; then
    javac --release 17 -cp "$ZSTD_JAR:$OUT:$BENCH_CP" -d "$OUT" \
        "$ROOT/bench/adapters/HdiffBenchCodec.java"
fi

CP="$ZSTD_JAR:$OUT"
if [ -n "${BENCH_CP:-}" ]; then
    CP="$CP:$BENCH_CP"
fi
# BENCH_CLASS selects an addon codec class to call directly (Mixin is unavailable in a plain JVM).
# The addon's tree-capture seam is inactive, so encode times include parsing both sides.
JAVA_ARGS=()
if [ -n "${BENCH_CLASS:-}" ]; then
    JAVA_ARGS+=("-Dbench.codecClass=$BENCH_CLASS")
fi
# shellcheck disable=SC2206
if [ -n "${BENCH_JAVA_OPTS:-}" ]; then
    JAVA_ARGS+=(${BENCH_JAVA_OPTS})
fi
java "${JAVA_ARGS[@]}" -cp "$CP" CodecBench "$CODEC_ID" "$FIXTURES"
