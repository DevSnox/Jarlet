# Jarlet's own local persistence layer for plugin management.
#
# Sourced (not exec'd) into plugin.sh's process. Everything here is pure
# local bookkeeping -- reading/writing the per-server plugins-state.json
# and rewriting jarlet.toml -- and has no knowledge of any plugin source
# (hangar, spiget, ...). Contrast with router.sh, which decides which
# source adapter to call, and the adapters themselves (e.g.
# ../source/plugin/hangar.sh), which talk to those sources' APIs.
#
# May assume plugin.sh has already defined: fail(), SCRIPT_DIR, and that
# jq/dasel are already confirmed to be on PATH.

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
