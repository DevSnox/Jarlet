# Resolves a user-supplied CLI identifier to a concrete (source, id) pair.
#
# Sourced (not exec'd) into plugin.sh's process, before router.sh and
# commands.sh (both call into this). This file has no persistence
# knowledge of its own (see store.sh) and does not talk to any adapter
# (see router.sh/../adapter/plugin/*.sh) -- it only decides WHICH
# (source, id) a CLI-level `add <identifier>`, `remove <identifier>`, or
# `update <identifier>` refers to, before the existing add/remove/update
# logic (which has always operated on a concrete (source, id) pair) runs
# unchanged.
#
# Background: an `id` can only ever be declared under ONE source at a time
# for a given server (global per-server id uniqueness -- see
# resolve_check_id_available()), which is what makes resolving a bare
# identifier against already-declared entries unambiguous (see
# resolve_declared_identifier()), and is also why `add` no longer takes a
# `<source>` positional argument at all -- it's inferred (see
# resolve_add_identifier()) unless the caller passes `--source` explicitly
# to skip inference.
#
# May assume plugin.sh has already defined: fail(), sys_config_value(),
# $USER_AGENT, and that jq is already confirmed to be on PATH. Functions
# here that hit the network additionally require curl -- checked lazily,
# at call time, since remove/update resolution (resolve_declared_identifier)
# and an explicit `--source` add never need the network at all.
#
# RESOLVED_SOURCE / RESOLVED_ID: the two "out parameters" every resolve_*
# function in this file sets on success (Bash 3.2 has no way to return a
# struct/array cleanly, and these are consumed immediately by the caller,
# so plain global-ish variables -- reassigned by whichever resolve_*
# function runs last -- are the simplest option here, same spirit as
# router.sh's indirect-expansion workaround for no `declare -A`).
RESOLVED_SOURCE=""
RESOLVED_ID=""

# Unauthenticated GET, discarding the body, printing only the HTTP status
# code. Used purely as an existence probe (2xx vs. everything else) for
# both Hangar and Spiget reads -- confirmed live (see
# prototyping/changelog.md) that Hangar's GET /projects/{slugOrId} works
# fully unauthenticated (200/404, no 401/403), despite the research doc's
# general "auth required for essentially every endpoint" claim; that claim
# does not hold for this read path, so no JWT/API key machinery is needed
# here at all.
resolve_http_status() {
    local url="$1"
    local status

    status="$(
        curl \
            --silent \
            --output /dev/null \
            --write-out '%{http_code}' \
            --location \
            --header "User-Agent: $USER_AGENT" \
            "$url"
    )" || status="000"

    printf '%s' "$status"
}

# True (0) if the Hangar project slugOrId exists (2xx), false otherwise
# (404 or any other non-2xx, including network failure -- treated the same
# as "not found" here, since resolution's job is only to pick a source,
# not to distinguish network trouble from a genuine miss).
resolve_probe_hangar_project() {
    local slug_or_id="$1"
    local api status

    command -v curl >/dev/null || fail "curl is required to resolve a plugin's source"
    api="$(sys_config_value HANGAR_API)"
    status="$(resolve_http_status "$api/projects/$slug_or_id")"

    [[ "$status" == 2* ]]
}

# True (0) if the Spiget resource id exists (2xx), false otherwise.
resolve_probe_spiget_resource() {
    local id="$1"
    local api status

    command -v curl >/dev/null || fail "curl is required to resolve a plugin's source"
    api="$(sys_config_value SPIGET_API)"
    status="$(resolve_http_status "$api/resources/$id")"

    [[ "$status" == 2* ]]
}

# Searches Spiget by name and returns (as a JSON array on stdout) only the
# results whose "name" field case-insensitively EXACTLY matches -- not
# substring/contains -- each element trimmed down to {name, id}. An empty
# array means no exact match; the caller decides what zero/one/many means.
resolve_spiget_exact_name_matches() {
    local name="$1"
    local api encoded_name results

    command -v curl >/dev/null || fail "curl is required to resolve a plugin's source"
    api="$(sys_config_value SPIGET_API)"

    # jq's @uri format percent-encodes the identifier for safe use as a
    # path segment (names can contain spaces/punctuation) -- jq is already
    # a hard dependency, so no extra tool is needed just for this.
    encoded_name="$(jq -rn --arg s "$name" '$s | @uri')"

    results="$(
        curl \
            --silent \
            --show-error \
            --location \
            --header "User-Agent: $USER_AGENT" \
            "$api/search/resources/$encoded_name?field=name"
    )" || fail "Spiget search request failed for '$name'"

    jq -c --arg name "$name" '
        [ .[]
          | select((.name // "") | ascii_downcase == ($name | ascii_downcase))
          | {name: .name, id: .id}
        ]
    ' <<<"$results"
}

# Resolves a bare `add <identifier>` (no --source override) to a (source,
# id) pair, per this algorithm:
#   - contains "/"                -> github-releases, id = identifier as-is
#   - all digits                  -> probe hangar then spiget by that id;
#                                     whichever responds wins; hangar wins
#                                     on the rare double-hit; fail if
#                                     neither responds
#   - otherwise (a name)          -> try hangar exact slug first; else
#                                     spiget exact-name search, requiring
#                                     exactly one exact match (its
#                                     numeric id, not the search string,
#                                     becomes the resolved id)
#
# Sets RESOLVED_SOURCE/RESOLVED_ID on success. Fails (exits, via fail())
# on no-match/ambiguous-match -- never guesses.
resolve_add_identifier() {
    local identifier="$1"

    if [[ "$identifier" == */* ]]; then
        RESOLVED_SOURCE="github-releases"
        RESOLVED_ID="$identifier"
        return 0
    fi

    if [[ "$identifier" =~ ^[0-9]+$ ]]; then
        local hangar_ok=0 spiget_ok=0

        resolve_probe_hangar_project "$identifier" && hangar_ok=1
        resolve_probe_spiget_resource "$identifier" && spiget_ok=1

        if (( hangar_ok == 1 && spiget_ok == 1 )); then
            printf 'Warning: "%s" exists as both a Hangar project id and a Spiget resource id; defaulting to hangar (pass --source spiget to force the other)\n' "$identifier" >&2
            RESOLVED_SOURCE="hangar"
            RESOLVED_ID="$identifier"
        elif (( hangar_ok == 1 )); then
            RESOLVED_SOURCE="hangar"
            RESOLVED_ID="$identifier"
        elif (( spiget_ok == 1 )); then
            RESOLVED_SOURCE="spiget"
            RESOLVED_ID="$identifier"
        else
            fail "No plugin found with id '$identifier' on hangar or spiget"
        fi

        return 0
    fi

    if resolve_probe_hangar_project "$identifier"; then
        RESOLVED_SOURCE="hangar"
        RESOLVED_ID="$identifier"
        return 0
    fi

    local matches count
    matches="$(resolve_spiget_exact_name_matches "$identifier")"
    count="$(jq 'length' <<<"$matches")"

    if (( count == 1 )); then
        RESOLVED_SOURCE="spiget"
        RESOLVED_ID="$(jq -r '.[0].id' <<<"$matches")"
        return 0
    fi

    if (( count == 0 )); then
        fail "No exact match for '$identifier' on hangar or spiget. Use the numeric Spiget resource id directly, or check spelling, or pass --source explicitly."
    fi

    local listing
    listing="$(jq -r '.[] | "  - \(.name) (id \(.id))"' <<<"$matches")"
    fail "Multiple exact matches for '$identifier' on spiget; retry with --source spiget <numeric id>:
$listing"
}

# Validates that `id` is the right shape for an explicit `--source`
# override, mirroring each adapter's own id-shape check
# (process_hangar_plugin/process_spiget_plugin/process_github_releases_plugin
# in ../adapter/plugin/*.sh) so a bad id is rejected here, before
# jarlet.toml is ever rewritten, instead of surfacing later from inside
# the adapter.
resolve_validate_source_id_shape() {
    local source="$1" id="$2"

    case "$source" in
        hangar)
            [[ "$id" =~ ^[0-9A-Za-z._-]+$ ]] ||
                fail "--source hangar requires a valid Hangar project slug or id, got '$id'"
            ;;
        spiget)
            [[ "$id" =~ ^[0-9]+$ ]] ||
                fail "--source spiget requires a numeric Spiget resource id, got '$id'"
            ;;
        github-releases)
            [[ "$id" =~ ^[0-9A-Za-z._-]+/[0-9A-Za-z._-]+$ ]] ||
                fail "--source github-releases requires an 'owner/repo' id, got '$id'"
            ;;
        *)
            fail "Unknown --source '$source' (expected hangar, spiget, or github-releases)"
            ;;
    esac
}

# Fails if `id` is already declared under ANY source (global per-server id
# uniqueness: an id can only ever belong to one source at a time). Called
# by cmd_add() after resolution (whether inferred or via --source) has
# produced a concrete id, so it always checks the actual id that would be
# persisted -- not necessarily the raw identifier the user typed (e.g. a
# spiget name-search resolves to a numeric id different from the typed
# name).
resolve_check_id_available() {
    local plugins_json="$1" id="$2"
    local existing_source

    existing_source="$(
        jq -r --arg id "$id" \
            '[(.plugins // [])[] | select(.id == $id)] | (first // {}).source // empty' \
            <<<"$plugins_json"
    )"

    [[ -z "$existing_source" ]] ||
        fail "'$id' is already declared under source '$existing_source'; remove it first if you want to redeclare it under a different source"
}

# Resolves a bare `remove <identifier>` / `update <identifier>` against
# the currently declared [[plugins]] entries by id alone -- source is no
# longer needed as CLI input, since ids are globally unique per server
# (enforced at declare time by resolve_check_id_available()). Sets
# RESOLVED_SOURCE/RESOLVED_ID on success. `context` is only used to make
# the "not found" message name the subcommand that failed.
resolve_declared_identifier() {
    local plugins_json="$1" identifier="$2" context="$3"
    local matches count

    matches="$(
        jq -c --arg id "$identifier" \
            '[(.plugins // [])[] | select(.id == $id)]' \
            <<<"$plugins_json"
    )"
    count="$(jq 'length' <<<"$matches")"

    if (( count == 0 )); then
        fail "No declared plugin with id '$identifier' (for $context)"
    fi

    # Can't legitimately happen given resolve_check_id_available()'s
    # invariant -- but if jarlet.toml was hand-edited around it, fail
    # loudly instead of silently acting on the first match.
    if (( count > 1 )); then
        fail "Internal consistency error: id '$identifier' is declared under more than one source in jarlet.toml; fix this by hand before retrying"
    fi

    RESOLVED_SOURCE="$(jq -r '.[0].source' <<<"$matches")"
    RESOLVED_ID="$identifier"
}
