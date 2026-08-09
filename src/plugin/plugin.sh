#!/usr/bin/env bash
set -euo pipefail

readonly SCRIPT_DIR="$(
    CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd
)"

# shellcheck source=../lib/lib.sh
. "$SCRIPT_DIR/../lib/lib.sh"

readonly PROJECT_NAME="$(sys_config_value PROJECT_NAME)"
readonly REPO_URL="$(sys_config_value REPO_URL)"

readonly VERSION_SCRIPT="$SCRIPT_DIR/../lib/version.sh"
[[ -x "$VERSION_SCRIPT" ]] ||
    fail "$VERSION_SCRIPT does not exist or is not executable"

JARLET_VERSION="$("$VERSION_SCRIPT")" ||
    fail "Could not determine jarlet version"
readonly JARLET_VERSION

readonly USER_AGENT="${PROJECT_NAME}/${JARLET_VERSION} (${REPO_URL})"

# Set by dispatch_plugin() the first time a "hangar" entry is dispatched, so
# sources/hangar.sh is sourced (and its deps checked) lazily and only once
# per invocation, no matter how many entries route through it or which
# subcommand (add/remove/update) triggered the dispatch.
HANGAR_LOADED=0

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

# Prints the locally-recorded installed "file" (jar filename under
# plugins/) for source+id, or nothing if no state is recorded yet. Used by
# remove to find the jar to delete without trusting user-supplied input as
# a filename.
read_installed_file() {
    local server_dir="$1" source="$2" id="$3"
    local file
    file="$(plugin_state_file "$server_dir")"

    [[ -f "$file" ]] || return 0

    jq -r \
        --arg source "$source" \
        --arg id "$id" \
        '(.[] | select(.source == $source and .id == $id) | .file) // empty' \
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

# Companion to write_installed_version(): drops the source+id entry from
# plugins-state.json entirely (used by remove, which uninstalls rather than
# just stops tracking). A no-op if no state file exists yet.
remove_installed_version() {
    local server_dir="$1" source="$2" id="$3"
    local file tmp

    file="$(plugin_state_file "$server_dir")"
    [[ -f "$file" ]] || return 0

    tmp="$(mktemp "$server_dir/.plugins-state.XXXXXX")"
    trap 'rm -f "$tmp"' RETURN

    jq \
        --arg source "$source" \
        --arg id "$id" \
        'map(select(.source != $source or .id != $id))' \
        "$file" >"$tmp"

    mv "$tmp" "$file"
    trap - RETURN
}

# Persists a full plugins_json document (as produced by toml_to_json() and
# then mutated with jq) back to toml_file, via json_to_toml(). See
# json_to_toml()'s doc comment in lib.sh for why this is a full, lossy
# rewrite (comments/formatting are not preserved) -- only add/remove call
# this; update never does.
write_toml_file() {
    local toml_file="$1" json="$2"
    local tmp

    tmp="$(mktemp "$(dirname "$toml_file")/.$(basename "$toml_file").XXXXXX")"
    trap 'rm -f "$tmp"' RETURN

    json_to_toml <<<"$json" >"$tmp"
    mv "$tmp" "$toml_file"
    trap - RETURN
}

# Routes a single declared entry (source/id/policy_json) to its source
# adapter. Shared by the update-all loop, a targeted `update <source> <id>`,
# and add's immediate post-declare fetch, so all three go through the exact
# same dispatch path.
dispatch_plugin() {
    local server_dir="$1" plugins_dir="$2" source="$3" id="$4" policy_json="$5"

    case "$source" in
        hangar)
            if (( ! HANGAR_LOADED )); then
                command -v curl >/dev/null || fail "curl is required for the hangar source"
                command -v shasum >/dev/null || fail "shasum is required for the hangar source"
                # shellcheck source=../source/plugin/hangar.sh
                . "$SCRIPT_DIR/../source/plugin/hangar.sh"
                HANGAR_LOADED=1
            fi
            process_hangar_plugin "$server_dir" "$plugins_dir" "$id" "$policy_json"
            ;;
        *)
            printf 'Skipping "%s" (%s): only the hangar source is implemented\n' "$id" "$source"
            ;;
    esac
}

# Runs the identify->check-version->update flow for every declared plugin.
# This is exactly today's (pre-subcommand) plugins.sh behavior, preserved
# both as the `update` subcommand with no target and as the bare
# `plugins.sh <name>` invocation with no subcommand at all.
run_update_all() {
    local server_dir="$1" plugins_dir="$2" plugins_json="$3"
    local count i entry source id policy_json

    count="$(jq '(.plugins // []) | length' <<<"$plugins_json")"

    if (( count == 0 )); then
        printf 'No plugins declared\n'
        return 0
    fi

    for (( i = 0; i < count; i++ )); do
        entry="$(jq -c ".plugins[$i]" <<<"$plugins_json")"
        source="$(jq -r '.source' <<<"$entry")"
        id="$(jq -r '.id' <<<"$entry")"
        policy_json="$(jq -c '.policy' <<<"$entry")"

        dispatch_plugin "$server_dir" "$plugins_dir" "$source" "$id" "$policy_json"
    done
}

# Runs the identify->check-version->update flow for exactly one declared
# entry, identified by (source, id) -- the same key plugins-state.json
# already uses.
run_update_one() {
    local plugins_json="$1" server_dir="$2" plugins_dir="$3" source="$4" id="$5"
    local entry policy_json

    entry="$(
        jq -c \
            --arg source "$source" \
            --arg id "$id" \
            '(.plugins // []) | map(select(.source == $source and .id == $id)) | first // empty' \
            <<<"$plugins_json"
    )"

    [[ -n "$entry" ]] ||
        fail "No declared plugin with source '$source' and id '$id'"

    policy_json="$(jq -c '.policy' <<<"$entry")"
    dispatch_plugin "$server_dir" "$plugins_dir" "$source" "$id" "$policy_json"
}

# `plugins.sh <name> add <source> <id> [--pin <version> | --channel <name>]`
#
# Declares a new [[plugins]] entry in jarlet.toml (rewriting the whole file
# via write_toml_file(), see that function and json_to_toml()'s doc comment
# in lib.sh for the comment/formatting tradeoff) and then immediately
# fetches it via the normal dispatch path, matching plugin-management.md's
# declare-then-act model but folding both steps into one command for
# convenience.
cmd_add() {
    local server_dir="$1" plugins_dir="$2" toml_file="$3" plugins_json="$4"
    shift 4

    (( $# >= 2 )) ||
        fail "Usage: plugins.sh <name> add <source> <id> [--pin <version> | --channel <name>]"

    local source="$1" id="$2"
    shift 2

    local pin="" channel=""
    while (( $# > 0 )); do
        case "$1" in
            --pin)
                (( $# >= 2 )) || fail "--pin requires a value"
                pin="$2"
                shift 2
                ;;
            --channel)
                (( $# >= 2 )) || fail "--channel requires a value"
                channel="$2"
                shift 2
                ;;
            *)
                fail "Unknown option '$1' for add"
                ;;
        esac
    done

    [[ -z "$pin" || -z "$channel" ]] ||
        fail "--pin and --channel are mutually exclusive"

    local exists
    exists="$(
        jq \
            --arg source "$source" \
            --arg id "$id" \
            '[(.plugins // [])[] | select(.source == $source and .id == $id)] | length' \
            <<<"$plugins_json"
    )"
    (( exists == 0 )) ||
        fail "'$id' ($source) is already declared in $toml_file; remove it first or edit the file directly"

    # policy shape follows the documented { pin = "..." } / { track =
    # "channel", channel = "..." } forms (see jarlet.toml's example entry).
    # With neither flag given, default to tracking the "Release" channel --
    # matching hangar.sh's own internal default (`.channel // "Release"`)
    # for entries that omit a channel.
    local policy_json
    if [[ -n "$pin" ]]; then
        policy_json="$(jq -n --arg pin "$pin" '{pin: $pin}')"
    else
        [[ -n "$channel" ]] || channel="Release"
        policy_json="$(jq -n --arg channel "$channel" '{track: "channel", channel: $channel}')"
    fi

    local entry_json new_json
    entry_json="$(
        jq -n \
            --arg source "$source" \
            --arg id "$id" \
            --argjson policy "$policy_json" \
            '{source: $source, id: $id, policy: $policy}'
    )"

    new_json="$(jq --argjson entry "$entry_json" '.plugins = ((.plugins // []) + [$entry])' <<<"$plugins_json")"

    printf 'Note: this rewrites %s in full via dasel; hand-written comments and formatting are not preserved.\n' "$toml_file"
    write_toml_file "$toml_file" "$new_json"
    printf 'Declared "%s" (%s) in %s\n' "$id" "$source" "$toml_file"

    dispatch_plugin "$server_dir" "$plugins_dir" "$source" "$id" "$policy_json"
}

# `plugins.sh <name> remove <source> <id>`
#
# Full uninstall: drops the [[plugins]] entry from jarlet.toml (rewriting
# the whole file, same tradeoff as add), deletes the installed jar from
# plugins/ if one is on record, and clears the plugins-state.json entry.
cmd_remove() {
    local server_dir="$1" plugins_dir="$2" toml_file="$3" plugins_json="$4"
    shift 4

    (( $# == 2 )) ||
        fail "Usage: plugins.sh <name> remove <source> <id>"

    local source="$1" id="$2"

    local exists
    exists="$(
        jq \
            --arg source "$source" \
            --arg id "$id" \
            '[(.plugins // [])[] | select(.source == $source and .id == $id)] | length' \
            <<<"$plugins_json"
    )"
    (( exists > 0 )) ||
        fail "No declared plugin with source '$source' and id '$id' in $toml_file"

    local new_json
    new_json="$(
        jq \
            --arg source "$source" \
            --arg id "$id" \
            '.plugins = ((.plugins // []) | map(select(.source != $source or .id != $id)))' \
            <<<"$plugins_json"
    )"

    printf 'Note: this rewrites %s in full via dasel; hand-written comments and formatting are not preserved.\n' "$toml_file"
    write_toml_file "$toml_file" "$new_json"

    local jar_file
    jar_file="$(read_installed_file "$server_dir" "$source" "$id")"

    if [[ -n "$jar_file" && -f "$plugins_dir/$jar_file" ]]; then
        rm -f "$plugins_dir/$jar_file"
        printf 'Deleted %s\n' "$plugins_dir/$jar_file"
    fi

    remove_installed_version "$server_dir" "$source" "$id"

    printf 'Removed "%s" (%s) from %s\n' "$id" "$source" "$toml_file"
}

usage() {
    printf 'Usage: %s <name> [add <source> <id> [--pin <version> | --channel <name>] | remove <source> <id> | update [<source> <id>]]\n' "$0" >&2
}

main() {
    if (( $# < 1 )); then
        usage
        exit 2
    fi

    local name="$1"
    shift

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

    local subcommand="${1:-}"
    case "$subcommand" in
        add)
            shift
            cmd_add "$server_dir" "$plugins_dir" "$toml_file" "$plugins_json" "$@"
            ;;
        remove)
            shift
            cmd_remove "$server_dir" "$plugins_dir" "$toml_file" "$plugins_json" "$@"
            ;;
        update)
            shift
            if (( $# == 0 )); then
                run_update_all "$server_dir" "$plugins_dir" "$plugins_json"
            elif (( $# == 2 )); then
                run_update_one "$plugins_json" "$server_dir" "$plugins_dir" "$1" "$2"
            else
                fail "Usage: $0 <name> update [<source> <id>]"
            fi
            ;;
        "")
            # Backward-compatible default: bare `plugins.sh <name>` behaves
            # exactly like `plugins.sh <name> update` with no target --
            # process every declared plugin.
            run_update_all "$server_dir" "$plugins_dir" "$plugins_json"
            ;;
        *)
            usage
            fail "Unknown subcommand '$subcommand' (expected add, remove, or update)"
            ;;
    esac
}

main "$@"
