#!/usr/bin/env bash
# Uploads the four node jars to Modrinth and CurseForge.
#
#   ./build.sh            # produces build/libs/<version>/<id>-<loader>-<version>+<mc>.jar
#   ./publish.sh --dry-run
#   MODRINTH_TOKEN=... MODRINTH_PROJECT_ID=... ./publish.sh
#
# Project ids live in stonecutter.properties.toml (mod.publish_*) so the same metadata drives every
# node; tokens never go into the repository and are read from the environment only. A platform is
# skipped, not failed, when its id or token is missing: the release can be uploaded to one platform
# first and the other later.
set -euo pipefail
cd "$(dirname "$0")"

DRY=0
TYPE=release
CHANGELOG_FILE="CHANGELOG.md"
while [ $# -gt 0 ]; do
    case "$1" in
        --dry-run) DRY=1 ;;
        --type) shift; TYPE="${1:-release}" ;;
        --changelog) shift; CHANGELOG_FILE="${1:-CHANGELOG.md}" ;;
        -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
    shift
done

VERSION=$(grep -E '^mod\.version' stonecutter.properties.toml | head -1 | cut -d'"' -f2)
MOD_ID=$(grep -E '^mod\.id' stonecutter.properties.toml | head -1 | cut -d'"' -f2)
MOD_NAME=$(grep -E '^mod\.name' stonecutter.properties.toml | head -1 | cut -d'"' -f2)
# Environment overrides win, so a CI job can point at a staging project without editing the file.
MODRINTH_PROJECT_ID="${MODRINTH_PROJECT_ID:-$(grep -E '^mod\.publish_modrinth' stonecutter.properties.toml | head -1 | cut -d'"' -f2)}"
CURSEFORGE_PROJECT_ID="${CURSEFORGE_PROJECT_ID:-$(grep -E '^mod\.publish_curseforge' stonecutter.properties.toml | head -1 | cut -d'"' -f2)}"

[ -n "$VERSION" ] || { echo "mod.version missing from stonecutter.properties.toml" >&2; exit 1; }
[ -n "$MOD_ID" ] || { echo "mod.id missing from stonecutter.properties.toml" >&2; exit 1; }

# Endpoints are overridable so the upload path can be exercised against a local mock.
MODRINTH_API="${MODRINTH_API:-https://api.modrinth.com/v2}"
CURSEFORGE_API="${CURSEFORGE_API:-https://minecraft.curseforge.com/api}"

CHANGELOG=""
if [ -f "$CHANGELOG_FILE" ]; then
    CHANGELOG=$(cat "$CHANGELOG_FILE")
fi

# Reads mod.mc_releases for one Minecraft section of the properties file. Falls back to the section
# name when the key is absent, so a new node still publishes with a sane version list.
game_versions_for() {
    local mc="$1"
    local out
    out=$(awk -v want="$mc" '
        /^\[/ { section = $0; gsub(/[[:space:]"]/, "", section); gsub(/^\[|\]$/, "", section); next }
        section == want && /^mod\.mc_releases/ {
            line = $0
            sub(/.*\[/, "", line)
            sub(/\].*/, "", line)
            gsub(/[[:space:]"]/, "", line)
            print line
            exit
        }' stonecutter.properties.toml)
    if [ -z "$out" ]; then
        echo "$mc"
    else
        echo "$out"
    fi
}

json_escape() {
    printf '%s' "$1" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read())[1:-1])'
}

upload_modrinth() {
    local jar="$1" loader="$2" game_versions="$3" version_number="$4" title="$5"
    if [ -z "$MODRINTH_PROJECT_ID" ] || [ -z "${MODRINTH_TOKEN:-}" ]; then
        echo "  modrinth: skipped (set mod.publish_modrinth and MODRINTH_TOKEN)"
        return 0
    fi
    local json
    json=$(cat <<JSON
{
  "project_id": "$MODRINTH_PROJECT_ID",
  "version_number": "$version_number",
  "name": "$(json_escape "$title")",
  "changelog": "$(json_escape "$CHANGELOG")",
  "version_type": "$TYPE",
  "loaders": ["$loader"],
  "game_versions": [$(echo "$game_versions" | sed 's/,/", "/g; s/^\(.*\)$/"\1"/')],
  "file_parts": ["file"],
  "featured": true
}
JSON
)
    if [ "$DRY" = 1 ]; then
        echo "  modrinth: POST $MODRINTH_API/version (project $MODRINTH_PROJECT_ID)"
        echo "$json" | sed 's/^/    /'
        return 0
    fi
    # The JSON contains ';' (changelog prose), which curl's -F parser would treat as a form
    # parameter separator. Hand the body over as a file so it is sent verbatim.
    local data_file response
    data_file=$(mktemp)
    printf '%s' "$json" > "$data_file"
    response=$(curl -sS -X POST "$MODRINTH_API/version" \
        -H "Authorization: $MODRINTH_TOKEN" \
        -F "data=<$data_file;type=application/json" \
        -F "file=@$jar") || { rm -f "$data_file"; return 1; }
    rm -f "$data_file"
    if echo "$response" | grep -q '"id"'; then
        echo "  modrinth: uploaded"
    else
        echo "  modrinth: FAILED: $response" >&2
        return 1
    fi
}

# CurseForge's `gameVersions` takes numeric ids; `gameVersionNames` takes the names shown on the
# site, including the environment ("Client"/"Server") and loader ("Forge"/"Fabric"/"NeoForge").
curseforge_loader() {
    case "$1" in
        forge) echo "Forge" ;;
        fabric) echo "Fabric" ;;
        neoforge) echo "NeoForge" ;;
        *) echo "$1" ;;
    esac
}

# CurseForge file relations. Fabric requires ForgeConfigAPIPort; the optional time tree needs UILib.
curseforge_relations() {
    local loader="$1"
    local projects='{"slug":"ui","type":"optionalDependency"}'
    if [ "$loader" = "fabric" ]; then
        projects="{\"slug\":\"forge-config-api-port\",\"type\":\"requiredDependency\"},$projects"
    fi
    printf '{"projects":[%s]}' "$projects"
}

upload_curseforge() {
    local jar="$1" loader="$2" game_versions="$3" title="$4"
    if [ -z "$CURSEFORGE_PROJECT_ID" ] || [ -z "${CURSEFORGE_TOKEN:-}" ]; then
        echo "  curseforge: skipped (set mod.publish_curseforge and CURSEFORGE_TOKEN)"
        return 0
    fi
    # CurseForge rejects uploads without an environment selection; the mod runs on both sides.
    local cf_versions="$game_versions,Client,Server,$(curseforge_loader "$loader")"
    local metadata
    metadata=$(cat <<JSON
{
  "changelog": "$(json_escape "$CHANGELOG")",
  "changelogType": "markdown",
  "displayName": "$(json_escape "$title")",
  "releaseType": "$TYPE",
  "gameVersionNames": [$(echo "$cf_versions" | sed 's/,/", "/g; s/^\(.*\)$/"\1"/')],
  "relations": $(curseforge_relations "$loader")
}
JSON
)
    if [ "$DRY" = 1 ]; then
        echo "  curseforge: POST $CURSEFORGE_API/projects/$CURSEFORGE_PROJECT_ID/upload-file"
        echo "$metadata" | sed 's/^/    /'
        return 0
    fi
    # Same ';' hazard as above: send the metadata as a file, not an inline -F value.
    local metadata_file response
    metadata_file=$(mktemp)
    printf '%s' "$metadata" > "$metadata_file"
    response=$(curl -sS -X POST \
        "$CURSEFORGE_API/projects/$CURSEFORGE_PROJECT_ID/upload-file?token=$CURSEFORGE_TOKEN" \
        -F "metadata=<$metadata_file;type=application/json" \
        -F "file=@$jar") || { rm -f "$metadata_file"; return 1; }
    rm -f "$metadata_file"
    if echo "$response" | grep -q '"id"'; then
        echo "  curseforge: uploaded"
    else
        echo "  curseforge: FAILED: $response" >&2
        return 1
    fi
}

status=0
for node in 1.21.1-neoforge 1.21.1-fabric 1.20.1-forge 1.20.1-fabric; do
    mc="${node%%-*}"
    loader="${node##*-}"
    jar="build/libs/$VERSION/$MOD_ID-$loader-$VERSION+$mc.jar"
    echo "$node"
    if [ ! -f "$jar" ]; then
        echo "  missing $jar (run ./build.sh first)" >&2
        status=1
        continue
    fi
    game_versions=$(game_versions_for "$mc")
    version_number="$VERSION+$mc"
    title="$MOD_NAME $VERSION for Minecraft $mc ($loader)"
    upload_modrinth "$jar" "$loader" "$game_versions" "$version_number" "$title" || status=1
    upload_curseforge "$jar" "$loader" "$game_versions" "$title" || status=1
done

if [ "$DRY" = 1 ]; then
    echo "dry run: nothing was uploaded"
fi
exit $status
