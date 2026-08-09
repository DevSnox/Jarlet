# Resolves a source's "hosted externally, install manually" gate to
# another source adapter Jarlet already has, when the external URL is
# recognizable as belonging to it -- e.g. Spiget's EssentialsX resource
# (id 9089) reports `file.externalUrl:
# https://github.com/EssentialsX/Essentials/releases/tag/2.22.0` (confirmed
# live against api.spiget.org), a URL github-releases.sh can actually fetch
# from instead of the plugin just being skipped.
#
# Sourced (not exec'd) into plugin.sh's process, after router.sh (needs
# router.sh's load_adapter()/adapter_loaded_var()/adapter_url_matcher_var())
# and after store.sh/lib.sh (needs write_toml_file()/toml_to_json()/
# json_to_toml()/template_filename(), all already required by the time any
# adapter runs). Called from within an external-hosting gate in an adapter
# file itself (../adapter/plugin/spiget.sh, ../adapter/plugin/hangar.sh), not
# from plugin.sh's main() directly.
#
# Discovery is generic, not hardcoded to github-releases: every adapter file
# under the same directory route_plugin()/load_adapter() already load
# adapters from is tried in turn, and only those that set the optional
# ADAPTER_URL_MATCHER (see router.sh's header) are asked whether they
# recognize the URL. Wiring up recognition for another source later (e.g. if
# Hangar or Spiget URLs are ever found to point at each other -- checked live
# against both APIs while building this, no evidence found yet) means that
# adapter setting ADAPTER_URL_MATCHER; nothing here needs to change.
#
# The glob below mirrors load_adapter()'s own adapter-file path
# (../adapter/plugin) so both sides always agree on where adapters live.

# Given an external URL, tries every adapter that declares an
# ADAPTER_URL_MATCHER to see if it recognizes the URL as its own. On a
# match, sets REDIRECT_SOURCE/REDIRECT_ID/REDIRECT_POLICY_JSON and returns
# 0. Returns 1 (leaving those three empty) if `url` is empty or no loaded
# adapter's matcher recognizes it.
try_resolve_external_url() {
    local url="$1"
    REDIRECT_SOURCE="" REDIRECT_ID="" REDIRECT_POLICY_JSON=""

    [[ -n "$url" ]] || return 1

    local adapter_file candidate_source matcher_var matcher
    for adapter_file in "$SCRIPT_DIR"/../adapter/plugin/*.sh; do
        [[ -f "$adapter_file" ]] || continue

        candidate_source="$(basename "$adapter_file" .sh)"

        load_adapter "$candidate_source" || continue

        matcher_var="$(adapter_url_matcher_var "$candidate_source")"
        matcher="${!matcher_var:-}"
        [[ -n "$matcher" ]] || continue

        if "$matcher" "$url"; then
            REDIRECT_SOURCE="$candidate_source"
            REDIRECT_ID="$MATCHED_ID"
            REDIRECT_POLICY_JSON="$MATCHED_POLICY_JSON"
            return 0
        fi
    done

    return 1
}

# Rewrites the jarlet.toml [[plugins]] entry declared as (old_source,
# old_id) to instead declare (new_source, new_id, new_policy_json). Called
# once, the first time try_resolve_external_url() successfully redirects a
# source's external-hosting gate -- persisted rather than re-resolved every
# run, per the same "stable id behind the scenes" precedent as Spiget's
# name-search -> numeric-id resolution (resolve.sh): future updates route
# straight to new_source's adapter with no redirect-parsing repeated, and
# jarlet.toml accurately reflects where the plugin is actually fetched from.
#
# No plugins-state.json cleanup is needed for (old_source, old_id): every
# external-hosting gate in every adapter runs strictly before that adapter
# ever calls write_installed_version(), so no state entry under the old key
# is ever written in the first place.
persist_external_redirect() {
    local server_dir="$1" old_source="$2" old_id="$3" new_source="$4" new_id="$5" new_policy_json="$6"
    local toml_file plugins_json new_json

    toml_file="$server_dir/$(template_filename)"
    [[ -f "$toml_file" ]] ||
        fail "$toml_file does not exist; cannot persist external redirect for '$old_id'"

    plugins_json="$(toml_to_json "$toml_file")" ||
        fail "Could not parse $toml_file as TOML while persisting external redirect for '$old_id'"

    new_json="$(
        jq \
            --arg old_source "$old_source" \
            --arg old_id "$old_id" \
            --arg new_source "$new_source" \
            --arg new_id "$new_id" \
            --argjson new_policy "$new_policy_json" \
            '.plugins = ((.plugins // []) | map(
                if .source == $old_source and .id == $old_id then
                    {source: $new_source, id: $new_id, policy: $new_policy}
                else
                    .
                end
            ))' \
            <<<"$plugins_json"
    )"

    printf 'Note: this rewrites %s in full via dasel; hand-written comments and formatting are not preserved.\n' "$toml_file"
    write_toml_file "$toml_file" "$new_json"
    printf 'Updated declaration in %s: "%s" (%s) -> "%s" (%s)\n' "$toml_file" "$old_id" "$old_source" "$new_id" "$new_source"
}
