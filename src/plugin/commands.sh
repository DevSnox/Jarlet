# `add`/`remove` subcommand implementations for plugin.sh.
#
# Sourced (not exec'd) into plugin.sh's process. Each command parses its
# own arguments, mutates jarlet.toml via store.sh's write_toml_file(), and
# (for add) immediately routes the new entry via router.sh's
# route_plugin(). May assume plugin.sh has already defined: fail(),
# SCRIPT_DIR, and that store.sh and router.sh have already been sourced.

# `plugins.sh <name> add <source> <id> [--pin <version> | --channel <name>]`
#
# Declares a new [[plugins]] entry in jarlet.toml (rewriting the whole file
# via write_toml_file(), see that function and json_to_toml()'s doc comment
# in lib.sh for the comment/formatting tradeoff) and then immediately
# fetches it via the normal routing path, matching plugin-management.md's
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

    route_plugin "$server_dir" "$plugins_dir" "$source" "$id" "$policy_json"
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
