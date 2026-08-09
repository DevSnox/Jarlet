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
# run_update_one() additionally relies on resolve.sh's
# resolve_declared_identifier() having already been sourced.
#
# An adapter's ADAPTER_URL_MATCHER is optional (unlike ADAPTER_SOURCE_NAME/
# ADAPTER_ENTRY_FUNCTION) -- only an adapter another source's external-hosting
# URL might plausibly point at needs to set one. See redirect.sh's header for
# the full mechanism this enables and ../adapter/plugin/github-releases.sh's
# github_releases_match_url() for the one adapter that sets it today.

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

# Companion to adapter_loaded_var(), same bash-3.2-compatible mechanism:
# holds the optional ADAPTER_URL_MATCHER a loaded adapter may have set (see
# load_adapter() below), namespaced per source for the same reason
# adapter_loaded_var() is -- redirect.sh's try_resolve_external_url() loads
# more than one adapter file in sequence while looking for a matcher, and a
# plain (non-namespaced) variable would just get clobbered by each load.
adapter_url_matcher_var() {
    printf 'ADAPTER_URL_MATCHER_%s' "${1//-/_}"
}

# Idempotently loads (sources) the adapter file for `source`, if one exists.
# Extracted out of route_plugin() so redirect.sh's try_resolve_external_url()
# can also load adapters (to inspect their optional ADAPTER_URL_MATCHER)
# without duplicating this sourcing/sanity-check logic or risking a
# double-source of a readonly-var-declaring adapter file. Returns 1 (touching
# nothing) if no adapter file exists for `source`; the caller decides how to
# report that -- route_plugin()'s missing-adapter message differs from
# try_resolve_external_url()'s silent "not a match, try the next one".
#
# On success, adapter_loaded_var("$source") holds ADAPTER_ENTRY_FUNCTION, and
# -- only if the adapter set one -- adapter_url_matcher_var("$source") holds
# ADAPTER_URL_MATCHER.
load_adapter() {
    local source="$1"
    local adapter_file="$SCRIPT_DIR/../adapter/plugin/$source.sh"

    [[ -f "$adapter_file" ]] || return 1

    local loaded_var
    loaded_var="$(adapter_loaded_var "$source")"

    if [[ -z "${!loaded_var:-}" ]]; then
        local ADAPTER_SOURCE_NAME="" ADAPTER_ENTRY_FUNCTION="" ADAPTER_URL_MATCHER=""
        # shellcheck source=/dev/null
        . "$adapter_file"

        [[ "$ADAPTER_SOURCE_NAME" == "$source" ]] ||
            fail "Adapter '$adapter_file' declares ADAPTER_SOURCE_NAME='$ADAPTER_SOURCE_NAME', expected '$source'"

        [[ -n "$ADAPTER_ENTRY_FUNCTION" ]] ||
            fail "Adapter '$adapter_file' did not set ADAPTER_ENTRY_FUNCTION"

        declare -F "$ADAPTER_ENTRY_FUNCTION" >/dev/null ||
            fail "Adapter '$adapter_file' declares ADAPTER_ENTRY_FUNCTION='$ADAPTER_ENTRY_FUNCTION' but that function is not defined"

        if [[ -n "$ADAPTER_URL_MATCHER" ]]; then
            declare -F "$ADAPTER_URL_MATCHER" >/dev/null ||
                fail "Adapter '$adapter_file' declares ADAPTER_URL_MATCHER='$ADAPTER_URL_MATCHER' but that function is not defined"

            printf -v "$(adapter_url_matcher_var "$source")" '%s' "$ADAPTER_URL_MATCHER"
        fi

        printf -v "$loaded_var" '%s' "$ADAPTER_ENTRY_FUNCTION"
    fi

    return 0
}

# Routes a single declared entry (source/id/policy_json) to its source
# adapter. Shared by the update-all loop, a targeted `update <source> <id>`,
# add's immediate post-declare fetch, and redirect.sh's external-URL
# resolution (once it has rewritten jarlet.toml to the redirected
# source/id, it calls back into this exact same path to actually fetch),
# so all of them go through the exact same routing path.
#
# trust_requested ("true"/"false", default "false" when omitted) is
# threaded straight through to the adapter's entry function as its final
# argument -- see trust.sh's header and hangar.sh's/spiget.sh's external-
# hosting gates for what it does. Kept as an explicit parameter (not an
# implicit global) for the same reason server_dir/policy_json are: it's
# how every other piece of per-invocation state already flows through this
# call chain.
route_plugin() {
    local server_dir="$1" plugins_dir="$2" source="$3" id="$4" policy_json="$5"
    local trust_requested="${6:-false}"

    if ! load_adapter "$source"; then
        printf 'Skipping "%s" (%s): no adapter is implemented for this source\n' "$id" "$source"
        return 0
    fi

    local loaded_var
    loaded_var="$(adapter_loaded_var "$source")"

    "${!loaded_var}" "$server_dir" "$plugins_dir" "$id" "$policy_json" "$trust_requested"
}

# Runs the identify->check-version->update flow for every declared plugin.
# This is exactly today's (pre-subcommand) plugins.sh behavior, preserved
# both as the `update` subcommand with no target and as the bare
# `plugins.sh <name>` invocation with no subcommand at all.
run_update_all() {
    local server_dir="$1" plugins_dir="$2" plugins_json="$3"
    local trust_requested="${4:-false}"
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

        route_plugin "$server_dir" "$plugins_dir" "$source" "$id" "$policy_json" "$trust_requested"
    done
}

# Runs the identify->check-version->update flow for exactly one declared
# entry, identified by a bare `identifier` -- resolved against the
# currently declared entries by id alone via resolve.sh's
# resolve_declared_identifier() (source is no longer needed as input,
# since ids are globally unique per server; see resolve.sh's header
# comment). Once resolved, this looks up the same (source, id) key
# plugins-state.json already uses.
run_update_one() {
    local plugins_json="$1" server_dir="$2" plugins_dir="$3" identifier="$4"
    local trust_requested="${5:-false}"
    local source id entry policy_json

    resolve_declared_identifier "$plugins_json" "$identifier" "update"
    source="$RESOLVED_SOURCE"
    id="$RESOLVED_ID"

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
    route_plugin "$server_dir" "$plugins_dir" "$source" "$id" "$policy_json" "$trust_requested"
}
