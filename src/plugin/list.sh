# `list` subcommand implementation for plugin.sh.
#
# Sourced (not exec'd) into plugin.sh's process. Read-only: combines the
# DECLARED [[plugins]] entries from jarlet.toml (already parsed into
# plugins_json by plugin.sh's main()) with the INSTALLED state recorded in
# plugins-state.json (via store.sh's read_all_installed()) into one
# per-plugin view, then renders it as a simple, flag-driven static page
# (no interactive/curses-style paging -- this is a prototype). May assume
# plugin.sh has already defined: fail(), sys_config_value(), and that
# store.sh has already been sourced (read_all_installed()).

# `plugins.sh <name> list [--page <n>] [--all]`
#
# --page <n>: 1-indexed page number, PLUGIN_LIST_PAGE_SIZE entries per
# page (centralised in jarlet-sys.conf per this project's "all
# value-containing variables live in jarlet-sys.conf" rule -- see
# sys_config_value() in lib.sh). Defaults to page 1.
# --all: bypasses pagination entirely and prints every declared plugin.
# --page and --all are mutually exclusive.
cmd_list() {
    local server_dir="$1" plugins_json="$2"
    shift 2

    local page=1 show_all=0
    while (( $# > 0 )); do
        case "$1" in
            --page)
                (( $# >= 2 )) || fail "--page requires a value"
                page="$2"
                shift 2
                ;;
            --all)
                show_all=1
                shift
                ;;
            *)
                fail "Unknown option '$1' for list"
                ;;
        esac
    done

    [[ "$page" =~ ^[0-9]+$ && "$page" -ge 1 ]] ||
        fail "--page must be a positive integer"

    local installed_json merged_json count
    installed_json="$(read_all_installed "$server_dir")"

    merged_json="$(
        jq -n \
            --argjson declared "$plugins_json" \
            --argjson installed "$installed_json" \
            '
                (($declared.plugins // [])
                    | map(. as $d |
                        (($installed | map(select(.source == $d.source and .id == $d.id)) | first) // {}) as $i |
                        {
                            source: $d.source,
                            id: $d.id,
                            policy: $d.policy,
                            version_name: ($i.version_name // null),
                            channel_name: ($i.channel_name // null)
                        }
                    )
                    | sort_by(.source, .id)
                )
            '
    )"

    count="$(jq 'length' <<<"$merged_json")"

    if (( count == 0 )); then
        printf 'No plugins declared\n'
        return 0
    fi

    local page_size start end total_pages
    page_size="$(sys_config_value PLUGIN_LIST_PAGE_SIZE)"
    [[ "$page_size" =~ ^[0-9]+$ && "$page_size" -ge 1 ]] ||
        fail "PLUGIN_LIST_PAGE_SIZE in jarlet-sys.conf must be a positive integer"

    if (( show_all )); then
        start=0
        end=$count
    else
        total_pages=$(( (count + page_size - 1) / page_size ))
        (( page <= total_pages )) ||
            fail "Page $page does not exist (there are $total_pages page(s))"

        start=$(( (page - 1) * page_size ))
        end=$(( start + page_size ))
        (( end <= count )) || end=$count
    fi

    local i entry id source version_name pin channel version_display policy_display
    for (( i = start; i < end; i++ )); do
        entry="$(jq -c ".[$i]" <<<"$merged_json")"
        id="$(jq -r '.id' <<<"$entry")"
        source="$(jq -r '.source' <<<"$entry")"
        version_name="$(jq -r '.version_name // empty' <<<"$entry")"
        pin="$(jq -r '.policy.pin // empty' <<<"$entry")"
        channel="$(jq -r '.policy.channel // empty' <<<"$entry")"

        version_display="${version_name:-not installed}"
        if [[ -n "$pin" ]]; then
            policy_display="pin: $pin"
        elif [[ -n "$channel" ]]; then
            policy_display="channel: $channel"
        else
            policy_display="-"
        fi

        printf '  %s (%s) -- %s [%s]\n' "$id" "$source" "$version_display" "$policy_display"
    done

    if (( ! show_all )); then
        printf 'Page %d of %d (%d plugin(s) total)' "$page" "$total_pages" "$count"
        if (( page < total_pages )); then
            printf ' -- use --page %d for more\n' "$(( page + 1 ))"
        else
            printf '\n'
        fi
    fi
}
