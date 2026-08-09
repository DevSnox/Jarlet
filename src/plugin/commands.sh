# `add`/`remove` subcommand implementations for plugin.sh.
#
# Sourced (not exec'd) into plugin.sh's process. Each command parses its
# own arguments, resolves a bare user identifier to a concrete (source, id)
# pair via resolve.sh, mutates jarlet.toml via store.sh's
# write_toml_file(), and (for add) immediately routes the new entry via
# router.sh's route_plugin(). May assume plugin.sh has already defined:
# fail(), SCRIPT_DIR, and that store.sh, resolve.sh, and router.sh have
# already been sourced.

# `plugins.sh <name> add <identifier> [--pin <version> | --channel <name>] [--source <hangar|spiget|github-releases>]`
#
# `source` is no longer a positional argument -- it's inferred from
# `identifier` by resolve.sh's resolve_add_identifier() (owner/repo ->
# github-releases, numeric -> probe hangar/spiget, name -> hangar exact
# slug then spiget exact-name search), unless --source is given as an
# explicit escape hatch to skip inference entirely (still validated
# against that source's expected id shape).
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

    (( $# >= 1 )) ||
        fail "Usage: plugins.sh <name> add <identifier> [--pin <version> | --channel <name>] [--source <hangar|spiget|github-releases>]"

    local identifier="$1"
    shift

    local pin="" channel="" source_override=""
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
            --source)
                (( $# >= 2 )) || fail "--source requires a value"
                source_override="$2"
                shift 2
                ;;
            *)
                fail "Unknown option '$1' for add"
                ;;
        esac
    done

    [[ -z "$pin" || -z "$channel" ]] ||
        fail "--pin and --channel are mutually exclusive"

    local source id
    if [[ -n "$source_override" ]]; then
        resolve_validate_source_id_shape "$source_override" "$identifier"
        source="$source_override"
        id="$identifier"
    else
        resolve_add_identifier "$identifier"
        source="$RESOLVED_SOURCE"
        id="$RESOLVED_ID"

        printf 'Resolved "%s" to %s (%s)\n' "$identifier" "$id" "$source"
    fi

    resolve_check_id_available "$plugins_json" "$id"

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

# `plugins.sh <name> remove <identifier>`
#
# `identifier` is resolved against the currently declared [[plugins]]
# entries by id alone via resolve.sh's resolve_declared_identifier() --
# `source` is no longer a positional argument, since ids are globally
# unique per server (see resolve.sh's header comment).
#
# Full uninstall: drops the [[plugins]] entry from jarlet.toml (rewriting
# the whole file, same tradeoff as add), deletes the installed jar from
# plugins/ if one is on record, and clears the plugins-state.json entry.
cmd_remove() {
    local server_dir="$1" plugins_dir="$2" toml_file="$3" plugins_json="$4"
    shift 4

    (( $# == 1 )) ||
        fail "Usage: plugins.sh <name> remove <identifier>"

    local identifier="$1"
    resolve_declared_identifier "$plugins_json" "$identifier" "remove"
    local source="$RESOLVED_SOURCE" id="$RESOLVED_ID"

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
