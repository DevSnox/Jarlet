#!/usr/bin/env bash
set -euo pipefail

readonly SCRIPT_DIR="$(
    CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd
)"

# shellcheck source=lib.sh
. "$SCRIPT_DIR/lib.sh"

readonly PROJECT_NAME="$(sys_config_value PROJECT_NAME)"
readonly REPO_URL="$(sys_config_value REPO_URL)"

readonly VERSION_SCRIPT="$SCRIPT_DIR/version.sh"
[[ -x "$VERSION_SCRIPT" ]] ||
    fail "$VERSION_SCRIPT does not exist or is not executable"

JARLET_VERSION="$("$VERSION_SCRIPT")" ||
    fail "Could not determine jarlet version"
readonly JARLET_VERSION

readonly USER_AGENT="${PROJECT_NAME}/${JARLET_VERSION} (${REPO_URL})"

plugin_state_file() {
    printf '%s/plugins-state.json' "$1"
}

# Prints the locally-recorded installed version_name for source+id, or
# nothing if no state is recorded yet.
read_installed_version() {
    local server_dir="$1" source="$2" id="$3"
    local file
    file="$(plugin_state_file "$server_dir")"

    [[ -f "$file" ]] || return 0

    jq -r \
        --arg source "$source" \
        --arg id "$id" \
        '(.[] | select(.source == $source and .id == $id) | .version_name) // empty' \
        "$file"
}

# Records/replaces the installed-version entry for source+id. version_json
# must be a JSON object with the InstalledVersion fields (version_name,
# version_id, channel_name, sha256, size, file, external). This is
# Jarlet-written state, not the human-authored jarlet.toml template, so it
# stays a separate JSON file next to it (same reasoning plugin-management.md
# gave for JSON: programmatically written, not hand-edited).
write_installed_version() {
    local server_dir="$1" source="$2" id="$3" version_json="$4"
    local file existing tmp

    file="$(plugin_state_file "$server_dir")"
    existing="[]"
    [[ -f "$file" ]] && existing="$(cat "$file")"

    tmp="$(mktemp "$server_dir/.plugins-state.XXXXXX")"
    trap 'rm -f "$tmp"' RETURN

    jq \
        --arg source "$source" \
        --arg id "$id" \
        --argjson entry "$version_json" \
        '
            map(select(.source != $source or .id != $id))
            + [({source: $source, id: $id} + $entry)]
        ' <<<"$existing" >"$tmp"

    mv "$tmp" "$file"
    trap - RETURN
}

main() {
    if (( $# < 1 )); then
        printf 'Usage: %s <name>\n' "$0" >&2
        exit 2
    fi

    local name="$1"

    [[ "$name" =~ ^[0-9A-Za-z._-]+$ ]] ||
        fail "Server name must be a simple name (letters, digits, ._-)"

    command -v jq >/dev/null || fail "jq is required"
    require_toml_tools

    local template_name
    template_name="$(template_filename)"

    local root server_dir toml_file
    root="$(servers_root)"
    server_dir="$root/$name"

    [[ -d "$server_dir" ]] ||
        fail "No server named '$name' found at $server_dir"

    toml_file="$server_dir/$template_name"

    [[ -f "$toml_file" ]] ||
        fail "$toml_file does not exist. Run setup.sh (or start.sh) for '$name' first to generate it."

    local plugins_json
    plugins_json="$(toml_to_json "$toml_file")" ||
        fail "Could not parse $toml_file as TOML"

    local plugins_dir
    plugins_dir="$server_dir/plugins"
    mkdir -p "$plugins_dir"

    local count
    count="$(jq '(.plugins // []) | length' <<<"$plugins_json")"

    if (( count == 0 )); then
        printf 'No plugins declared in %s\n' "$toml_file"
        return 0
    fi

    local i entry source id policy_json
    local hangar_loaded=0
    for (( i = 0; i < count; i++ )); do
        entry="$(jq -c ".plugins[$i]" <<<"$plugins_json")"
        source="$(jq -r '.source' <<<"$entry")"
        id="$(jq -r '.id' <<<"$entry")"
        policy_json="$(jq -c '.policy' <<<"$entry")"

        case "$source" in
            hangar)
                if (( ! hangar_loaded )); then
                    command -v curl >/dev/null || fail "curl is required for the hangar source"
                    command -v shasum >/dev/null || fail "shasum is required for the hangar source"
                    # shellcheck source=sources/hangar.sh
                    . "$SCRIPT_DIR/sources/hangar.sh"
                    hangar_loaded=1
                fi
                process_hangar_plugin "$server_dir" "$plugins_dir" "$id" "$policy_json"
                ;;
            *)
                printf 'Skipping "%s" (%s): only the hangar source is implemented\n' "$id" "$source"
                ;;
        esac
    done
}

main "$@"
