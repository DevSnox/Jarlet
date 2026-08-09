#!/usr/bin/env bash
set -euo pipefail

readonly SCRIPT_DIR="$(
    CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd
)"

readonly SYS_CONFIG_FILE="$SCRIPT_DIR/../jarlet-sys.conf"

fail() {
    printf 'Error: %s\n' "$1" >&2
    exit 1
}

sys_config_value() {
    local key="$1"

    [[ -f "$SYS_CONFIG_FILE" ]] ||
        fail "$SYS_CONFIG_FILE does not exist"

    local value
    value="$(
        awk -F= -v key="$key" '
            $0 !~ /^[[:space:]]*#/ && $1 == key {
                sub(/^[^=]*=/, "")
                print
                exit
            }
        ' "$SYS_CONFIG_FILE"
    )"

    [[ -n "$value" ]] ||
        fail "Missing required key '$key' in $SYS_CONFIG_FILE"

    printf '%s' "$value"
}

readonly PAPER_API="$(sys_config_value PAPER_API)"
readonly PROJECT_NAME="$(sys_config_value PROJECT_NAME)"
readonly REPO_URL="$(sys_config_value REPO_URL)"

readonly VERSION_SCRIPT="$SCRIPT_DIR/../lib/version.sh"
[[ -x "$VERSION_SCRIPT" ]] ||
    fail "$VERSION_SCRIPT does not exist or is not executable"

JARLET_VERSION="$("$VERSION_SCRIPT")" ||
    fail "Could not determine jarlet version"
readonly JARLET_VERSION

readonly USER_AGENT="${PROJECT_NAME}/${JARLET_VERSION} (${REPO_URL})"

download_paper() {
    local minecraft_version="$1"
    local target="${2:-server.jar}"

    [[ "$minecraft_version" =~ ^[0-9A-Za-z._-]+$ ]] ||
        fail "Invalid Minecraft version: $minecraft_version"

    local project_json
    project_json="$(
        curl \
            --fail \
            --silent \
            --show-error \
            --location \
            --header "User-Agent: $USER_AGENT" \
            "$PAPER_API/projects/paper"
    )" || fail "Could not query Paper versions"

    if ! jq -e \
        --arg version "$minecraft_version" \
        '[.versions[][]] | index($version) != null' \
        >/dev/null <<<"$project_json"
    then
        fail "Paper does not support Minecraft $minecraft_version"
    fi

    local builds_json
    builds_json="$(
        curl \
            --fail \
            --silent \
            --show-error \
            --location \
            --header "User-Agent: $USER_AGENT" \
            "$PAPER_API/projects/paper/versions/$minecraft_version/builds"
    )" || fail "Could not query builds for Minecraft $minecraft_version"

    local build_json
    build_json="$(
        jq -c '
            [
                .[]
                | select(
                    .channel == "STABLE"
                    and .downloads["server:default"] != null
                )
            ]
            | max_by(.id) // empty
        ' <<<"$builds_json"
    )"

    [[ -n "$build_json" ]] ||
        fail "No stable Paper build exists for Minecraft $minecraft_version"

    local build_id name url expected_hash expected_size

    build_id="$(jq -r '.id' <<<"$build_json")"
    name="$(jq -r '.downloads["server:default"].name' <<<"$build_json")"
    url="$(jq -r '.downloads["server:default"].url' <<<"$build_json")"
    expected_hash="$(jq -r '.downloads["server:default"].checksums.sha256' <<<"$build_json")"
    expected_size="$(jq -r '.downloads["server:default"].size' <<<"$build_json")"

    local target_dir temporary
    target_dir="$(dirname "$target")"
    mkdir -p "$target_dir"

    temporary="$(mktemp "$target_dir/.paper-download.XXXXXX")"
    trap 'rm -f "$temporary"' EXIT INT TERM

    printf 'Downloading Paper %s build %s\n' \
        "$minecraft_version" "$build_id"

    curl \
        --fail \
        --silent \
        --show-error \
        --location \
        --retry 3 \
        --header "User-Agent: $USER_AGENT" \
        --output "$temporary" \
        "$url" ||
        fail "Paper download failed"

    local actual_size
    actual_size="$(wc -c <"$temporary" | tr -d '[:space:]')"

    [[ "$actual_size" == "$expected_size" ]] ||
        fail "Paper download has the wrong size"

    local actual_hash
    actual_hash="$(shasum -a 256 "$temporary" | awk '{print $1}')"

    [[ "$actual_hash" == "$expected_hash" ]] ||
        fail "Paper SHA-256 verification failed"

    mv "$temporary" "$target"
    trap - EXIT INT TERM

    printf 'Installed %s as %s\n' "$name" "$target"
    printf 'SHA-256: %s\n' "$expected_hash"
}

main() {
    if (( $# < 1 )); then
        printf 'Usage: %s <minecraft-version> [target]\n' "$0" >&2
        exit 2
    fi

    command -v curl >/dev/null || fail "curl is required"
    command -v jq >/dev/null || fail "jq is required"
    command -v shasum >/dev/null || fail "shasum is required"

    download_paper "$1" "${2:-server.jar}"
}

main "$@"
