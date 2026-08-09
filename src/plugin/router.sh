# Routes declared plugin entries to their source adapter.
#
# Sourced (not exec'd) into plugin.sh's process. This file decides WHICH
# source adapter (hangar, spiget, ...) a given entry's `source` field maps
# to, and lazily sources/loads that adapter -- it is not itself an adapter
# and has no source-specific knowledge (compare ../adapter/plugin/hangar.sh,
# which knows how to talk to the Hangar API). It also owns the two
# "process every declared plugin" / "process one declared plugin" loops
# that walk plugins_json and call route_plugin() for each entry.
#
# Adapter discovery/dispatch is self-describing, not hard-typed: the
# `source` field of a declared entry names a file, ../adapter/plugin/<source>.sh,
# which is lazily sourced on first use. That file must, when sourced,
# set two variables -- ADAPTER_SOURCE_NAME (echoed back for a sanity check
# that the file actually serves the source it was loaded for) and
# ADAPTER_ENTRY_FUNCTION (the name of its entry-point function, called
# dynamically). This file contains zero source-specific string literals;
# see ../adapter/plugin/hangar.sh's header for the full adapter contract.
#
# May assume plugin.sh has already defined: fail(), SCRIPT_DIR, and that
# store.sh has already been sourced (route_plugin() is called by add too,
# which relies on store.sh's write_toml_file() having already run).

# Maps a loaded source name to its adapter's entry-point function, so a
# source is sourced (and its own deps checked) lazily and only once per
# invocation, no matter how many entries route through it or which
# subcommand (add/remove/update) triggered the routing.
#
# Bash 3.2 (macOS's default /usr/bin/bash, since Apple stopped bundling
# GPLv3 bash) has no associative arrays, so this is tracked via
# dynamically-named plain variables (indirect expansion, bash 2.x+, and
# printf -v, bash 3.1+) instead of `declare -A`. Do not reintroduce
# `declare -A` here -- it breaks on any user still on the system bash.
adapter_loaded_var() {
    printf 'ADAPTER_LOADED_%s' "${1//-/_}"
}

# Routes a single declared entry (source/id/policy_json) to its source
# adapter. Shared by the update-all loop, a targeted `update <source> <id>`,
# and add's immediate post-declare fetch, so all three go through the exact
# same routing path.
route_plugin() {
    local server_dir="$1" plugins_dir="$2" source="$3" id="$4" policy_json="$5"

    local adapter_file="$SCRIPT_DIR/../adapter/plugin/$source.sh"

    if [[ ! -f "$adapter_file" ]]; then
        printf 'Skipping "%s" (%s): no adapter is implemented for this source\n' "$id" "$source"
        return 0
    fi

    local loaded_var
    loaded_var="$(adapter_loaded_var "$source")"

    if [[ -z "${!loaded_var:-}" ]]; then
        local ADAPTER_SOURCE_NAME="" ADAPTER_ENTRY_FUNCTION=""
        # shellcheck source=/dev/null
        . "$adapter_file"

        [[ "$ADAPTER_SOURCE_NAME" == "$source" ]] ||
            fail "Adapter '$adapter_file' declares ADAPTER_SOURCE_NAME='$ADAPTER_SOURCE_NAME', expected '$source'"

        [[ -n "$ADAPTER_ENTRY_FUNCTION" ]] ||
            fail "Adapter '$adapter_file' did not set ADAPTER_ENTRY_FUNCTION"

        declare -F "$ADAPTER_ENTRY_FUNCTION" >/dev/null ||
            fail "Adapter '$adapter_file' declares ADAPTER_ENTRY_FUNCTION='$ADAPTER_ENTRY_FUNCTION' but that function is not defined"

        printf -v "$loaded_var" '%s' "$ADAPTER_ENTRY_FUNCTION"
    fi

    "${!loaded_var}" "$server_dir" "$plugins_dir" "$id" "$policy_json"
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

        route_plugin "$server_dir" "$plugins_dir" "$source" "$id" "$policy_json"
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
    route_plugin "$server_dir" "$plugins_dir" "$source" "$id" "$policy_json"
}
