#!/usr/bin/env bash
# Local three-phase capture chain: drive an already-running dev server over RCON, take three
# snapshots, and extract real *.old.nbt / *.new.nbt pairs for bench/codec-bench.sh.
#
# The server must be running with RCON enabled and the mod loaded. Start it first, for example:
#
#   ./gradlew --console=plain :1.21.1-neoforge:runServer
#
# Point run/server.properties at a new world first (a fresh level-name also gives a fresh vault):
#
#   level-name=chain-1.21.1-neoforge
#   level-seed=-1234567
#
# Use a fresh level-name and a fresh vault for every node. Run it on an empty dev server; a
# connected player would add unrelated chunk writes. The script never deletes a world or a
# vault; it refuses to run when either the phase-1 area or the vault already contains data,
# because the extractor would otherwise mix older versions into the new pairs.
#
# Phases:
#   step1  forceload a 14x14 chunk area (196 chunks) and snapshot the generated terrain
#   step2  build a 64x64 platform with a house, filled chests and four TNT charges, then snapshot;
#          s1s2 is split into build (platform box 36..39 x 4..7) and outside (every other chunk, tick-dominated)
#   step3  forceload 64 untouched chunks east of step1 and snapshot; the new chunks are
#          ABSENT->FULL (no pair), and old chunks rewritten during the phase go to outside
#
# Extraction is snapshot-aware (ExtractFixtures [fromSlot toSlot]): only targets whose state
# differs between two consecutive snapshots is emitted. Every loaded chunk is periodically
# rewritten with time-dependent NBT. Those rewrites are kept as the outside corpus, not filtered:
# they are the mod's real per-phase write amplification.
#
# Usage:
#   ./bench/chain-local.sh
#   CHAIN_NODE=1.21.1-neoforge ./bench/chain-local.sh
#   FIXTURES=bench/fixtures-local ./bench/codec-bench.sh zstd/1
#
# Environment:
#   CHAIN_GAME_DIR        dev server dir (default <repo>/run)
#   CHAIN_RCON_HOST       default 127.0.0.1
#   CHAIN_RCON_PORT       default rcon.port from server.properties, else 25575
#   CHAIN_RCON_PASS       default rcon.password from server.properties
#   CHAIN_NODE            label for fixture names (default: sanitised world key)
#   CHAIN_OUT             output root (default <repo>/bench/fixtures-local)
#   CHAIN_CHUNK_PAIRS     cap per chunk category (default 512; outside can exceed 150)
#   CHAIN_MIN_CHUNK_BYTES skip chunks smaller than this (default 1024)
#   CHAIN_WAIT_SECONDS    chunk-generation timeout (default 300)
#   CHAIN_READY_TIMEOUT   wait for the server/backup runtime (default 180)
#   CHAIN_CMD_TIMEOUT     per-RCON-command timeout (default 600)
#   CHAIN_SETTLE_SECONDS  TNT settle time before the step2 snapshot (default 8)
#   CHAIN_CODEC           codec id for CHAIN_BENCH (default zstd/1)
#   CHAIN_BENCH=1         run codec-bench.sh on the output at the end
#   CHAIN_ALLOW_EXISTING_VAULT=1  allow a non-empty vault (pairs may include old history)
#   CHAIN_ALLOW_EXISTING_WORLD=1  allow a phase-1 area that already has chunks
#   ZSTD_JAR              zstd-jni jar (default: newest in the Gradle cache)
#
# The output directory <CHAIN_OUT>/<CHAIN_NODE> is recreated on every run; it is gitignored.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GAME_DIR="${CHAIN_GAME_DIR:-$ROOT/run}"
OUT_ROOT="${CHAIN_OUT:-$ROOT/bench/fixtures-local}"
BUILD_DIR="$ROOT/bench/chain-build"

CHUNK_PAIRS="${CHAIN_CHUNK_PAIRS:-512}"
MIN_CHUNK_BYTES="${CHAIN_MIN_CHUNK_BYTES:-1024}"
WAIT_SECONDS="${CHAIN_WAIT_SECONDS:-300}"
CMD_TIMEOUT="${CHAIN_CMD_TIMEOUT:-600}"
READY_TIMEOUT="${CHAIN_READY_TIMEOUT:-180}"
SETTLE_SECONDS="${CHAIN_SETTLE_SECONDS:-8}"

# Phase-1 area: chunks 32..45 x 0..13 = 196 chunks, away from the spawn area so a fresh world
# really has none of them on disk. Phase-3 area: chunks 46..53 x 0..7 = 64 untouched chunks.
S1_X1=512; S1_Z1=0; S1_X2=735; S1_Z2=223
S3_X1=736; S3_Z1=0; S3_X2=863; S3_Z2=127

note() { printf '[chain] %s\n' "$*"; }
fail() { printf 'chain-local: %s\n' "$*" >&2; exit 1; }
show() {
    if [ -n "$1" ]; then
        printf '%s\n' "$1" | sed 's/^/    /'
    fi
}

PY_HELPER="$(mktemp "${TMPDIR:-/tmp}/minimalbackup-chain.XXXXXX.py")"
cleanup() { rm -f "$PY_HELPER"; }
trap cleanup EXIT

cat > "$PY_HELPER" <<'PY'
"""Small RCON client and region-header probe for bench/chain-local.sh."""
import os
import socket
import struct
import sys
import time


def pack(pid, ptype, body):
    payload = body.encode("utf-8")
    return struct.pack("<iii", len(payload) + 10, pid, ptype) + payload + b"\x00\x00"


def recv_exact(sock, count):
    data = b""
    while len(data) < count:
        chunk = sock.recv(count - len(data))
        if not chunk:
            raise ConnectionError("rcon connection closed")
        data += chunk
    return data


def read_packet(sock):
    length = struct.unpack("<i", recv_exact(sock, 4))[0]
    if length < 10 or length > (1 << 20):
        raise ValueError("bad rcon packet length %d" % length)
    payload = recv_exact(sock, length)
    pid, ptype = struct.unpack("<ii", payload[:8])
    return pid, ptype, payload[8:-2].decode("utf-8", "replace")


def connect(host, port, password, timeout):
    sock = socket.create_connection((host, port), timeout=timeout)
    sock.settimeout(timeout)
    sock.sendall(pack(1, 3, password))
    pid, ptype, _ = read_packet(sock)
    if pid == -1 or ptype != 2:
        sock.close()
        raise PermissionError("rcon authentication failed")
    # Vanilla sends an empty SERVERDATA_RESPONSE_VALUE after the auth response.
    sock.settimeout(0.2)
    try:
        while True:
            read_packet(sock)
    except (TimeoutError, ConnectionError, ValueError):
        pass
    return sock


def run(host, port, password, timeout, text):
    sock = connect(host, port, password, timeout)
    try:
        sock.settimeout(timeout)
        # Vanilla's RCON loop wraps the socket in a fresh BufferedInputStream for every
        # command, so two commands must never share one write. Send the command, read its
        # response, and only send the 'list' barrier as a separate packet if the command was
        # silent or is still running after the first second.
        sock.sendall(pack(2, 2, text))
        parts = []
        got = False
        sock.settimeout(min(1.0, timeout))
        try:
            pid, ptype, body = read_packet(sock)
            got = True
            if pid == 2 and ptype == 0 and body:
                parts.append(body)
        except (TimeoutError, socket.timeout):
            pass
        if not got:
            sock.settimeout(timeout)
            sock.sendall(pack(3, 2, "list"))
        while True:
            sock.settimeout(0.3 if got else timeout)
            try:
                pid, ptype, body = read_packet(sock)
            except (TimeoutError, socket.timeout):
                break
            if pid == 2 and ptype == 0 and body:
                parts.append(body)
            if pid == 3:
                break
        return "".join(parts)
    finally:
        sock.close()


def wait_status(host, port, password, timeout, text):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            left = max(1.0, min(30.0, deadline - time.monotonic()))
            reply = run(host, port, password, left, text)
        except PermissionError:
            raise
        except (OSError, ConnectionError, ValueError):
            time.sleep(1.0)
            continue
        if "world=" in reply:
            print(reply)
            return 0
        time.sleep(1.0)
    print("timed out waiting for 'backup status'", file=sys.stderr)
    return 1


def read_header(path):
    try:
        with open(path, "rb") as handle:
            return handle.read(4096)
    except FileNotFoundError:
        return None


def count_chunks(region_dir, x1, z1, x2, z2):
    cx1, cx2 = x1 >> 4, x2 >> 4
    cz1, cz2 = z1 >> 4, z2 >> 4
    headers = {}
    present = 0
    total = 0
    for cx in range(cx1, cx2 + 1):
        for cz in range(cz1, cz2 + 1):
            total += 1
            rx, rz = cx >> 5, cz >> 5
            key = (rx, rz)
            if key not in headers:
                headers[key] = read_header(
                    os.path.join(region_dir, "r.%d.%d.mca" % (rx, rz)))
            header = headers[key]
            if header is None:
                continue
            index = ((cx & 31) + (cz & 31) * 32) * 4
            if any(header[index:index + 3]):
                present += 1
    return present, total


def wait_chunks(region_dir, x1, z1, x2, z2, timeout):
    deadline = time.monotonic() + timeout
    last = -1
    while True:
        present, total = count_chunks(region_dir, x1, z1, x2, z2)
        if present != last:
            print("chunks %d/%d" % (present, total), file=sys.stderr, flush=True)
            last = present
        if present == total:
            return 0
        if time.monotonic() >= deadline:
            print("chunk generation timed out: %d/%d" % (present, total), file=sys.stderr)
            return 1
        time.sleep(1.0)


def main():
    if len(sys.argv) < 2:
        print("usage: rcon|wait-rcon|count-chunks|wait-chunks ...", file=sys.stderr)
        return 2
    mode = sys.argv[1]
    try:
        if mode == "rcon":
            if len(sys.argv) < 7:
                print("usage: rcon <host> <port> <password> <timeout> <command...>",
                      file=sys.stderr)
                return 2
            host = sys.argv[2]
            port = int(sys.argv[3])
            password = sys.argv[4]
            timeout = float(sys.argv[5])
            text = " ".join(sys.argv[6:])
            sys.stdout.write(run(host, port, password, timeout, text))
            return 0
        if mode == "wait-rcon":
            if len(sys.argv) != 6:
                print("usage: wait-rcon <host> <port> <password> <timeout>", file=sys.stderr)
                return 2
            return wait_status(sys.argv[2], int(sys.argv[3]), sys.argv[4],
                               float(sys.argv[5]), "backup status")
        if mode == "count-chunks":
            if len(sys.argv) != 7:
                print("usage: count-chunks <regionDir> <x1> <z1> <x2> <z2>", file=sys.stderr)
                return 2
            present, total = count_chunks(sys.argv[2], int(sys.argv[3]), int(sys.argv[4]),
                                          int(sys.argv[5]), int(sys.argv[6]))
            print("%d/%d" % (present, total))
            return 0
        if mode == "wait-chunks":
            if len(sys.argv) != 8:
                print("usage: wait-chunks <regionDir> <x1> <z1> <x2> <z2> <timeout>",
                      file=sys.stderr)
                return 2
            return wait_chunks(sys.argv[2], int(sys.argv[3]), int(sys.argv[4]),
                               int(sys.argv[5]), int(sys.argv[6]), float(sys.argv[7]))
        print("unknown mode: %s" % mode, file=sys.stderr)
        return 2
    except PermissionError as error:
        print("rcon: %s" % error, file=sys.stderr)
        return 3
    except (OSError, ConnectionError, ValueError) as error:
        print("rcon: %s" % error, file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
PY

command -v java >/dev/null 2>&1 || fail "java not found"
command -v javac >/dev/null 2>&1 || fail "javac not found"
command -v python3 >/dev/null 2>&1 || fail "python3 not found"

if [ -z "${ZSTD_JAR:-}" ]; then
    ZSTD_JAR="$(find "$HOME/.gradle/caches/modules-2/files-2.1/com.github.luben/zstd-jni" \
        -name 'zstd-jni-*.jar' ! -name '*-sources.jar' 2>/dev/null | sort | tail -n 1 || true)"
fi
if [ -z "${ZSTD_JAR:-}" ] || [ ! -f "$ZSTD_JAR" ]; then
    fail "zstd-jni jar not found; set ZSTD_JAR=/path/to/zstd-jni.jar"
fi

PROPS="$GAME_DIR/server.properties"
prop() {
    if [ -f "$PROPS" ]; then
        sed -n "s/^$1=//p" "$PROPS" | tail -n 1
    fi
}
if [ "$(prop enable-rcon)" = "false" ]; then
    fail "enable-rcon=false in $PROPS; set enable-rcon=true and restart the server"
fi
HOST="${CHAIN_RCON_HOST:-127.0.0.1}"
PORT="${CHAIN_RCON_PORT:-$(prop rcon.port)}"
PORT="${PORT:-25575}"
PASS="${CHAIN_RCON_PASS:-$(prop rcon.password)}"
PASS="${PASS:-dev}"

rcon() {
    python3 "$PY_HELPER" rcon "$HOST" "$PORT" "$PASS" "$CMD_TIMEOUT" "$@"
}

note "waiting for RCON at $HOST:$PORT (up to ${READY_TIMEOUT}s)"
if SERVER_STATUS="$(python3 "$PY_HELPER" wait-rcon "$HOST" "$PORT" "$PASS" "$READY_TIMEOUT")"; then
    :
else
    code=$?
    if [ "$code" -eq 3 ]; then
        fail "RCON authentication failed; check CHAIN_RCON_PASS / rcon.password"
    fi
    fail "no backup runtime over RCON; check that the server is up, enable-rcon=true, and the mod loaded"
fi

FLAT_STATUS="$(printf '%s' "$SERVER_STATUS" | tr '\n' ' ')"
WORLD="$(printf '%s' "$FLAT_STATUS" | sed -n 's/.*world=\(.*\) snapshots=[0-9][0-9]*.*/\1/p')"
SNAPSHOTS="$(printf '%s' "$FLAT_STATUS" | sed -n 's/.* snapshots=\([0-9][0-9]*\).*/\1/p')"
[ -n "$WORLD" ] || fail "cannot parse world= from: $FLAT_STATUS"
[ -n "$SNAPSHOTS" ] || fail "cannot parse snapshots= from: $FLAT_STATUS"
if printf '%s' "$FLAT_STATUS" | grep -q 'armed=yes'; then
    fail "a restore is armed for this world; run /backup disarm before capturing a chain"
fi

VAULT_ROOT="$GAME_DIR/mbackups/$WORLD"
WORLD_DIR="$GAME_DIR/$WORLD"
note "world=$WORLD snapshots=$SNAPSHOTS vault=$VAULT_ROOT"

if [ "$SNAPSHOTS" -gt 0 ] && [ "${CHAIN_ALLOW_EXISTING_VAULT:-0}" != "1" ]; then
    fail "vault already has $SNAPSHOTS snapshot(s); start with a fresh level-name or set CHAIN_ALLOW_EXISTING_VAULT=1 (pairs may include old history)"
fi

CONFIG="$GAME_DIR/config/minimalbackup-server.toml"
toml_value() {
    if [ ! -f "$CONFIG" ]; then
        return 0
    fi
    sed -n -E "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" "$CONFIG" | tail -n 1 | tr -d '[:space:]'
}
if [ -f "$CONFIG" ]; then
    [ "$(toml_value enabled)" != "false" ] || fail "backup.enabled=false in $CONFIG"
    [ "$(toml_value triggerOnManual)" != "false" ] || fail "backup.triggerOnManual=false in $CONFIG"
    [ "$(toml_value triggerOnInterval)" != "true" ] || fail "backup.triggerOnInterval=true can clear the buffer between phases; set it to false and run /backup reload"
    if [ "$(toml_value captureOnRead)" = "true" ]; then
        note "captureOnRead=true (default); set it to false for cleaner phase boundaries"
    fi
    if [ "$(toml_value triggerOnUnload)" = "true" ]; then
        note "triggerOnUnload=true; do not stop the server during the chain (extraction runs while it is up)"
    fi
    max_bytes="$(toml_value maxChangeSetBytes)"
    if [ -n "$max_bytes" ] && [ "$max_bytes" -lt 33554432 ]; then
        note "maxChangeSetBytes=$max_bytes is below 32 MiB; phase 1 may auto-split a snapshot"
    fi
else
    note "server config not found at $CONFIG; assuming runtime defaults"
fi

NODE="${CHAIN_NODE:-$WORLD}"
NODE="$(printf '%s' "$NODE" | tr -cs 'A-Za-z0-9._-' '_' | sed -E 's/^_+//; s/_+$//')"
[ -n "$NODE" ] || fail "empty node label"
case "$NODE" in
    .|..) fail "invalid node label: $NODE" ;;
esac
OUT_NODE="$OUT_ROOT/$NODE"
case "$OUT_NODE" in
    "$OUT_ROOT"/*) ;;
    *) fail "refusing to use output path $OUT_NODE" ;;
esac
rm -rf "$OUT_NODE"
mkdir -p "$OUT_NODE/build" "$OUT_NODE/outside" "$OUT_NODE/files"

note "checking that the phase-1 area is untouched"
COUNTS="$(python3 "$PY_HELPER" count-chunks "$WORLD_DIR/region" "$S1_X1" "$S1_Z1" "$S1_X2" "$S1_Z2")" \
    || fail "cannot read region headers under $WORLD_DIR/region"
PRESENT="${COUNTS%%/*}"
case "$PRESENT" in
    ''|*[!0-9]*) fail "unexpected chunk count: $COUNTS" ;;
esac
if [ "$PRESENT" -ne 0 ] && [ "${CHAIN_ALLOW_EXISTING_WORLD:-0}" != "1" ]; then
    fail "phase-1 area already has $PRESENT chunk(s); use a fresh level-name or set CHAIN_ALLOW_EXISTING_WORLD=1"
fi

note "compiling engine + ExtractFixtures -> $BUILD_DIR"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"
mapfile -t ENGINE_SRC < <(find "$ROOT/src/main/java/io/github/linkfgfgui/minimalbackup/engine" \
    -name '*.java' | sort)
javac --release 17 -cp "$ZSTD_JAR" -d "$BUILD_DIR" "${ENGINE_SRC[@]}" \
    "$ROOT/bench/tools/ExtractFixtures.java"

snapshot_count() {
    local text
    text="$(rcon "backup status")"
    printf '%s' "$text" | tr '\n' ' ' | sed -n 's/.* snapshots=\([0-9][0-9]*\).*/\1/p'
}

CREATED_SLOT=""
CREATED_STATS=""
create_snapshot() {
    local name="$1" before after out slot
    before="$(snapshot_count)"
    [ -n "$before" ] || fail "cannot read the snapshot count from /backup status"
    note "creating snapshot $name (snapshots=$before)"
    out="$(rcon "backup create $name")"
    printf '%s\n' "$out" | sed 's/^/    /'
    after="$(snapshot_count)"
    if [ -z "$after" ] || [ "$after" -le "$before" ]; then
        fail "snapshot '$name' was not created (before=$before after=$after)"
    fi
    slot="$(printf '%s' "$out" | tr '\n' ' ' | sed -n 's/.*Created \(S[0-9][0-9]*\).*/\1/p')"
    [ -n "$slot" ] || fail "snapshot '$name' was created but its slot could not be parsed from: $out"
    note "snapshot $name = $slot (snapshots=$after)"
    CREATED_SLOT="$slot"
    local flat entries logical physical
    flat="$(printf '%s' "$out" | tr '\n' ' ')"
    entries="$(printf '%s' "$flat" | sed -n 's/.*entries=\([0-9][0-9]*\) logical=.*/\1/p')"
    logical="$(printf '%s' "$flat" | sed -n 's/.*logical=\([0-9][0-9]*\) physical=.*/\1/p')"
    physical="$(printf '%s' "$flat" | sed -n 's/.*physical=\([0-9][0-9]*\).*/\1/p')"
    CREATED_STATS="entries=$entries logical=$logical physical=$physical"
}

count_in() {
    find "$1" -name '*.old.nbt' | wc -l | tr -d '[:space:]'
}
count_chunk_in() {
    find "$1" -name '*__chunk_*.old.nbt' | wc -l | tr -d '[:space:]'
}
count_label() {
    find "$1" -name "$2*.old.nbt" | wc -l | tr -d '[:space:]'
}

extract_chunks() {
    local label="$1" from="$2" to="$3" box="$4" outdir="$5"
    note "extracting $label ($from -> $to)"
    java -cp "$BUILD_DIR:$ZSTD_JAR" ExtractFixtures "$VAULT_ROOT" "$outdir" \
        "$NODE-$label" "$CHUNK_PAIRS" "region/" "$MIN_CHUNK_BYTES" \
        "$from" "$to" "$box" | sed 's/^/    /'
}

extract_files() {
    local label="$1" from="$2" to="$3" outdir="$4"
    note "extracting $label ($from -> $to)"
    java -cp "$BUILD_DIR:$ZSTD_JAR" ExtractFixtures "$VAULT_ROOT" "$outdir" \
        "$NODE-$label" 0 "" "$MIN_CHUNK_BYTES" "$from" "$to" "" \
        | sed 's/^/    /'
}

note "phase 1: generate 196 chunks (chunks 32..45 x 0..13)"
rcon "gamerule randomTickSpeed 0" >/dev/null
rcon "gamerule doMobSpawning false" >/dev/null
rcon "gamerule doDaylightCycle false" >/dev/null
rcon "gamerule doWeatherCycle false" >/dev/null
rcon "gamerule doFireTick false" >/dev/null
rcon "difficulty peaceful" >/dev/null
rcon "time set noon" >/dev/null
rcon "weather clear" >/dev/null
rcon "kill @e[type=!player]" >/dev/null
show "$(rcon "forceload add $S1_X1 $S1_Z1 $S1_X2 $S1_Z2")"
python3 "$PY_HELPER" wait-chunks "$WORLD_DIR/region" \
    "$S1_X1" "$S1_Z1" "$S1_X2" "$S1_Z2" "$WAIT_SECONDS" \
    || fail "phase-1 chunk generation timed out; raise CHAIN_WAIT_SECONDS"
create_snapshot "step1_init"
S1_SLOT="$CREATED_SLOT"
S1_STATS="$CREATED_STATS"

note "phase 2: build a 64x64 platform with a house, chests and four TNT charges"
rcon "fill 576 150 64 639 150 127 minecraft:stone" >/dev/null
rcon "fill 584 151 72 593 157 81 minecraft:stone_bricks hollow" >/dev/null
rcon "fill 589 151 72 590 153 72 minecraft:air" >/dev/null

chests=("586 152 75" "588 152 75" "586 152 77" "588 152 77")
items=(diamond gold_ingot iron_ingot redstone coal bread)
i=0
for pos in "${chests[@]}"; do
    rcon "setblock $pos minecraft:chest" >/dev/null
    for slot in 0 1 2; do
        item="${items[$(( (i + slot) % ${#items[@]} ))]}"
        rcon "item replace block $pos container.$slot with minecraft:$item 64" >/dev/null
    done
    i=$((i + 3))
done

rcon "summon minecraft:tnt 620 152 108 {fuse:40s}" >/dev/null
rcon "summon minecraft:tnt 628 152 112 {fuse:60s}" >/dev/null
rcon "summon minecraft:tnt 624 152 116 {fuse:80s}" >/dev/null
rcon "summon minecraft:tnt 616 152 112 {fuse:100s}" >/dev/null
note "waiting ${SETTLE_SECONDS}s for the explosions"
sleep "$SETTLE_SECONDS"
rcon "kill @e[type=!player]" >/dev/null
sleep 1

create_snapshot "step2_modifications"
S2_SLOT="$CREATED_SLOT"
S2_STATS="$CREATED_STATS"
# Platform x 576..639, z 64..127 = chunks 36..39 x 4..7.
extract_chunks "s1s2-build" "$S1_SLOT" "$S2_SLOT" "36,4,39,7" "$OUT_NODE/build"
extract_chunks "s1s2-outside" "$S1_SLOT" "$S2_SLOT" "!36,4,39,7" "$OUT_NODE/outside"
extract_files "s1s2" "$S1_SLOT" "$S2_SLOT" "$OUT_NODE/files"
s12_build="$(count_chunk_in "$OUT_NODE/build")"
s12_outside="$(count_label "$OUT_NODE/outside" "$NODE-s1s2-outside")"
if [ "$s12_build" -eq 0 ]; then
    fail "s1s2 produced no build chunks; the platform/TNT did not reach the vault (check /backup status and the server log)"
fi
note "s1s2: build=$s12_build outside=$s12_outside (cap $CHUNK_PAIRS)"

note "phase 3: generate 64 untouched chunks (chunks 46..53 x 0..7)"
rcon "forceload add $S3_X1 $S3_Z1 $S3_X2 $S3_Z2" >/dev/null
python3 "$PY_HELPER" wait-chunks "$WORLD_DIR/region" \
    "$S3_X1" "$S3_Z1" "$S3_X2" "$S3_Z2" "$WAIT_SECONDS" \
    || fail "phase-3 chunk generation timed out; raise CHAIN_WAIT_SECONDS"
create_snapshot "step3_exploration"
S3_SLOT="$CREATED_SLOT"
S3_STATS="$CREATED_STATS"
extract_chunks "s2s3-rewrites" "$S2_SLOT" "$S3_SLOT" "" "$OUT_NODE/outside"
extract_files "s2s3" "$S2_SLOT" "$S3_SLOT" "$OUT_NODE/files"
s23_rewrites="$(count_label "$OUT_NODE/outside" "$NODE-s2s3-rewrites")"
note "s2s3: rewrites=$s23_rewrites; the 64 new chunks are ABSENT->FULL (full-only)"

s12_build="$(count_chunk_in "$OUT_NODE/build")"
s12_outside="$(count_label "$OUT_NODE/outside" "$NODE-s1s2-outside")"
s23_rewrites="$(count_label "$OUT_NODE/outside" "$NODE-s2s3-rewrites")"
file_pairs="$(count_in "$OUT_NODE/files")"
note "fixtures: $OUT_NODE"
note "step1 $S1_STATS"
note "step2 $S2_STATS"
note "step3 $S3_STATS"
note "pairs: s1s2-build=$s12_build s1s2-outside=$s12_outside s2s3-rewrites=$s23_rewrites files=$file_pairs"
note "outside/rewrite pairs are kept: every loaded chunk is rewritten with time-dependent NBT"
note "server was left running; the vault is $VAULT_ROOT"

if [ "${CHAIN_BENCH:-0}" = "1" ]; then
    for category in build outside files; do
        if [ -n "$(find "$OUT_NODE/$category" -name '*.old.nbt' -print -quit)" ]; then
            note "benchmarking $category"
            FIXTURES="$OUT_NODE/$category" "$ROOT/bench/codec-bench.sh" "${CHAIN_CODEC:-zstd/1}"
        fi
    done
else
    note "next, per category:"
    note "  FIXTURES=$OUT_NODE/build ./bench/codec-bench.sh ${CHAIN_CODEC:-zstd/1}"
    note "  FIXTURES=$OUT_NODE/outside ./bench/codec-bench.sh ${CHAIN_CODEC:-zstd/1}"
    note "  FIXTURES=$OUT_NODE/files ./bench/codec-bench.sh ${CHAIN_CODEC:-zstd/1}"
fi
