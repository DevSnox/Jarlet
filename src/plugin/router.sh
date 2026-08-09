# Routes declared plugin entries to their source adapter.
#
# Sourced (not exec'd) into plugin.sh's process. This file decides WHICH
# source adapter (hangar, spiget, ...) a given entry's `source` field maps
# to, and lazily sources/loads that adapter -- it is not itself an adapter
# and has no source-specific knowledge (compare ../source/plugin/hangar.sh,
# which knows how to talk to the Hangar API). It also owns the two
# "process every declared plugin" / "process one declared plugin" loops
# that walk plugins_json and call route_plugin() for each entry.
#
# May assume plugin.sh has already defined: fail(), SCRIPT_DIR, and that
# store.sh has already been sourced (route_plugin() is called by add too,
# which relies on store.sh's write_toml_file() having already run).

# Set the first time a "hangar" entry is routed, so
# ../source/plugin/hangar.sh is sourced (and its deps checked) lazily and
# only once per invocation, no matter how many entries route through it or
# which subcommand (add/remove/update) triggered the routing.
HANGAR_LOADED=0

# Routes a single declared entry (source/id/policy_json) to its source
# adapter. Shared by the update-all loop, a targeted `update <source> <id>`,
# and add's immediate post-declare fetch, so all three go through the exact
# same routing path.
route_plugin() {
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
