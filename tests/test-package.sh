#!/usr/bin/env bash
# Packaging checks for the two release blockers found in real (non-dev) installs:
#  - Forge runs with SRG names, so its mixin config must name the generated refmap.
#  - The Fabric loader floor must match the node's ecosystem (1.20.1 cannot have >=0.19).
set -u
cd "$(dirname "$0")/.."
fail=0

forge=$(ls build/libs/*/minimalbackup-forge-*.jar 2>/dev/null | grep -v -- '-sources' | head -1)
if [ -n "$forge" ]; then
    if ! unzip -p "$forge" minimalbackup.mixins.json | grep -q '"refmap": "minimalbackup.refmap.json"'; then
        echo "FAIL $forge: mixin config does not name the refmap"
        fail=1
    fi
    if ! unzip -l "$forge" | grep -q 'minimalbackup.refmap.json'; then
        echo "FAIL $forge: refmap file missing"
        fail=1
    fi
fi

for jar in build/libs/*/minimalbackup-fabric-*.jar; do
    [ -f "$jar" ] || continue
    case "$jar" in *sources*) continue;; esac
    floor=$(unzip -p "$jar" fabric.mod.json | python3 -c '
import json, sys, re
dep = json.load(sys.stdin)["depends"]["fabricloader"]
print(re.sub(r"[^0-9.]", "", dep))
')
    case "$jar" in
        *1.20.1*) want=0.15 ;;
        *)        want=0.16 ;;
    esac
    if [ "$floor" != "$want" ]; then
        echo "FAIL $jar: fabricloader floor is '$floor', expected '$want'"
        fail=1
    fi
    configs=$(unzip -p "$jar" fabric.mod.json | sed -n 's/.*"mixins":\[\([^]]*\)\].*/\1/p')
    for cfg in minimalbackup.mixins.json minimalbackup.1211.mixins.json; do
        if echo "$configs" | grep -q "$cfg"; then
            if ! unzip -l "$jar" | grep -q "$cfg"; then
                echo "FAIL $jar: references $cfg but does not contain it"
                fail=1
            fi
        fi
    done
done

for jar in build/libs/*/minimalbackup-forge-*.jar build/libs/*/minimalbackup-neoforge-*.jar; do
    [ -f "$jar" ] || continue
    case "$jar" in *sources*) continue;; esac
    if unzip -l "$jar" | grep -q 'minimalbackup.fabric.mixins.json'; then
        echo "FAIL $jar: ships a Fabric-only mixin config"
        fail=1
    fi
done

if [ "$fail" -ne 0 ]; then
    echo "PACKAGE CHECKS FAILED"
    exit 1
fi
echo "PACKAGE CHECKS OK"
