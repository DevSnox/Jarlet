# Hangar plugin source adapter.
#
# Sourced (not exec'd) into plugins.sh's process by router.sh's
# route_plugin(), lazily and only once, the first time a declared plugin
# entry has source = "hangar" -- whether that routing came from the
# update-all loop, a targeted `update <source> <id>`, or add's immediate
# post-declare fetch. This keeps HANGAR_JWT process/env-scoped without
# inventing an inter-process protocol for passing auth state back, and
# avoids resolving HANGAR_API or requiring curl/shasum for templates that
# declare zero hangar plugins.
#
# Contract for future source adapters (spiget.sh, github-releases.sh):
#   - Self-describing, not hard-typed: when sourced, a source adapter MUST
#     set ADAPTER_SOURCE_NAME to its own source name (must match the
#     filename minus .sh -- router.sh sanity-checks this against the
#     `source` value it loaded the file for) and ADAPTER_ENTRY_FUNCTION to
#     the name of its entry point function. router.sh calls that function
#     dynamically; it contains no source-specific string literals of its
#     own, so file name = source name is the only convention it relies on.
#   - Entry point signature: process_<source>_plugin(server_dir,
#     plugins_dir, id, policy_json) -- the name itself is arbitrary (it's
#     read back from ADAPTER_ENTRY_FUNCTION), but keep this shape for
#     readability/consistency with existing adapters.
#   - May assume plugins.sh has already defined: fail(), config_value(),
#     sys_config_value(), $SCRIPT_DIR, $USER_AGENT, plugin_state_file(),
#     read_installed_version(), write_installed_version() (all from
#     store.sh), route_plugin() (router.sh), try_resolve_external_url()/
#     persist_external_redirect() (redirect.sh), and
#     handle_untrusted_external_url() (trust.sh -- the --trust fallback
#     tried after try_resolve_external_url() fails to recognize the URL),
#     and that jq/dasel are already confirmed to be on PATH.
#   - Entry point receives trust_requested ("true"/"false") as its 5th
#     argument, threaded from route_plugin() -- see trust.sh's header.
#   - Must check any dependencies of its own (curl, shasum, ...) at source
#     time, before ADAPTER_SOURCE_NAME/ADAPTER_ENTRY_FUNCTION are set;
#     plugins.sh does not check them unconditionally on its behalf.
#   - Must keep any credentials/tokens process/env-scoped only, never
#     written to disk — see HANGAR_JWT below.
#   - Optionally, if this source's URLs are ever something another
#     adapter's external-hosting gate might recognize, may also set
#     ADAPTER_URL_MATCHER to a function(url) that sets MATCHED_ID/
#     MATCHED_POLICY_JSON and returns 0 on a match, 1 otherwise -- see
#     redirect.sh's header and github-releases.sh's
#     github_releases_match_url() for the one adapter that does this today.

command -v curl >/dev/null || fail "curl is required for the hangar source"
command -v shasum >/dev/null || fail "shasum is required for the hangar source"

readonly HANGAR_API="$(sys_config_value HANGAR_API)"

# In-memory JWT for this process only. Seeded from JARLET_HANGAR_JWT if a
# wrapping shell session already exported one (so repeated invocations in
# the same session can skip re-authenticating), and re-exported under that
# same name after a fresh authenticate. Never written to disk anywhere;
# treated as ephemeral/session-scoped, not something meant to survive
# beyond the shell session that set it.
HANGAR_JWT="${JARLET_HANGAR_JWT:-}"

hangar_authenticate() {
    local api_key="${JARLET_HANGAR_API_KEY:-}"

    [[ -n "$api_key" ]] ||
        fail "JARLET_HANGAR_API_KEY is not set. Export your Hangar API key first: export JARLET_HANGAR_API_KEY='<your-hangar-api-key>'"

    local response
    response="$(
        curl \
            --fail \
            --silent \
            --show-error \
            --request POST \
            --header "User-Agent: $USER_AGENT" \
            --data-urlencode "apiKey=$api_key" \
            "$HANGAR_API/authenticate"
    )" || fail "Hangar authentication failed"

    HANGAR_JWT="$(jq -r '.token // empty' <<<"$response")"

    [[ -n "$HANGAR_JWT" ]] ||
        fail "Hangar authentication did not return a token"

    export JARLET_HANGAR_JWT="$HANGAR_JWT"
}

# Authenticated GET against $HANGAR_API$path, returning the response body.
# Re-authenticates once and retries on a 401 (expired/invalid JWT).
hangar_get() {
    local path="$1"
    local raw status body

    [[ -n "$HANGAR_JWT" ]] || hangar_authenticate

    raw="$(
        curl \
            --silent \
            --show-error \
            --location \
            --header "User-Agent: $USER_AGENT" \
            --header "Authorization: HangarAuth $HANGAR_JWT" \
            --write-out '\n%{http_code}' \
            "$HANGAR_API$path"
    )" || fail "Hangar request failed: $path"

    status="${raw##*$'\n'}"
    body="${raw%$'\n'*}"

    if [[ "$status" == "401" ]]; then
        hangar_authenticate

        raw="$(
            curl \
                --silent \
                --show-error \
                --location \
                --header "User-Agent: $USER_AGENT" \
                --header "Authorization: HangarAuth $HANGAR_JWT" \
                --write-out '\n%{http_code}' \
                "$HANGAR_API$path"
        )" || fail "Hangar request failed: $path"

        status="${raw##*$'\n'}"
        body="${raw%$'\n'*}"
    fi

    [[ "$status" == 2* ]] ||
        fail "Hangar request to $path failed with HTTP $status"

    printf '%s' "$body"
}

process_hangar_plugin() {
    local server_dir="$1" plugins_dir="$2" slug="$3" policy_json="$4"
    local trust_requested="${5:-false}"

    [[ "$slug" =~ ^[0-9A-Za-z._-]+$ ]] ||
        fail "Invalid Hangar project slug: $slug"

    local project_json visibility
    project_json="$(hangar_get "/projects/$slug")" ||
        fail "Could not fetch Hangar project '$slug'"

    visibility="$(jq -r '.visibility // empty' <<<"$project_json")"

    if [[ -n "$visibility" && "$visibility" != "public" ]]; then
        printf 'Skipping "%s": project visibility is "%s" (not public)\n' "$slug" "$visibility"
        return 0
    fi

    local pin channel target_version
    pin="$(jq -r '.pin // empty' <<<"$policy_json")"
    channel="$(jq -r '.channel // "Release"' <<<"$policy_json")"

    if [[ -n "$pin" ]]; then
        target_version="$pin"
    else
        target_version="$(hangar_get "/projects/$slug/latest?channel=$channel")" ||
            fail "Could not resolve latest version for '$slug' on channel '$channel'"
    fi

    [[ -n "$target_version" ]] ||
        fail "Hangar returned no version for '$slug'"

    local installed
    installed="$(read_installed_version "$server_dir" "hangar" "$slug")"

    if [[ "$installed" == "$target_version" ]]; then
        printf '"%s" is already up to date (%s)\n' "$slug" "$target_version"
        return 0
    fi

    local version_json visibility_v review_state
    version_json="$(hangar_get "/projects/$slug/versions/$target_version")" ||
        fail "Could not fetch version '$target_version' for '$slug'"

    visibility_v="$(jq -r '.visibility // empty' <<<"$version_json")"
    review_state="$(jq -r '.reviewState // empty' <<<"$version_json")"

    if [[ -n "$visibility_v" && "$visibility_v" != "public" ]]; then
        printf 'Skipping "%s" %s: version visibility is "%s"\n' "$slug" "$target_version" "$visibility_v"
        return 0
    fi

    # Hangar's reviewState enum (unreviewed/reviewed/under_review/partially_reviewed)
    # has no "rejected" state -- visibility is what actually gates public
    # availability, so any known reviewState is fine; skip only on an
    # unrecognized value.
    case "$review_state" in
        "" | unreviewed | reviewed | under_review | partially_reviewed) ;;
        *)
            printf 'Skipping "%s" %s: review state is "%s", not a recognized state\n' "$slug" "$target_version" "$review_state"
            return 0
            ;;
    esac

    local external_url
    external_url="$(jq -r '.downloads.PAPER.externalUrl // empty' <<<"$version_json")"

    if [[ -n "$external_url" ]]; then
        # Before giving up: try_resolve_external_url() (redirect.sh) may
        # recognize this URL as pointing at a source Jarlet already has a
        # working adapter for -- see spiget.sh's identical gate for the
        # concrete case this was built from (Spiget's externalUrl pointing
        # at a GitHub release) and redirect.sh's header for the full
        # mechanism. No live evidence was found of a Hangar externalUrl
        # ever pointing at Spiget/SpigotMC specifically, so only GitHub
        # URLs are recognized as of this writing -- if that ever changes,
        # it's a github-releases.sh (or a new adapter)'s ADAPTER_URL_MATCHER
        # to add, not something to special-case here.
        if try_resolve_external_url "$external_url"; then
            printf '"%s" %s is hosted externally at %s -- redirecting to %s (%s)\n' \
                "$slug" "$target_version" "$external_url" "$REDIRECT_ID" "$REDIRECT_SOURCE"

            persist_external_redirect "$server_dir" "hangar" "$slug" "$REDIRECT_SOURCE" "$REDIRECT_ID" "$REDIRECT_POLICY_JSON"
            route_plugin "$server_dir" "$plugins_dir" "$REDIRECT_SOURCE" "$REDIRECT_ID" "$REDIRECT_POLICY_JSON" "$trust_requested"
            return $?
        fi

        # Next fallback (trust.sh): the URL isn't recognizable as another
        # adapter's, but the user may have already trusted (or now be
        # trusting, via --trust) the domain it's hosted on -- see trust.sh's
        # header for the full mechanism. Hangar's fileInfo (its own
        # checksum/size) and its externalUrl are confirmed live to be
        # mutually exclusive on real data (Geyser: fileInfo=null whenever
        # externalUrl is set) -- but fileInfo is still read here and passed
        # through in case that ever isn't true for some other project;
        # handle_untrusted_external_url() only uses it if non-empty.
        local ext_hash ext_size
        ext_hash="$(jq -r '.downloads.PAPER.fileInfo.sha256Hash // empty' <<<"$version_json")"
        ext_size="$(jq -r '.downloads.PAPER.fileInfo.sizeBytes // empty' <<<"$version_json")"

        handle_untrusted_external_url \
            "$server_dir" "$plugins_dir" "hangar" "$slug" "$slug" \
            "$external_url" "$ext_hash" "$ext_size" "$slug.jar" "$trust_requested"
        return $?
    fi

    local file_name expected_hash expected_size
    file_name="$(jq -r '.downloads.PAPER.fileInfo.name // empty' <<<"$version_json")"
    expected_hash="$(jq -r '.downloads.PAPER.fileInfo.sha256Hash // empty' <<<"$version_json")"
    expected_size="$(jq -r '.downloads.PAPER.fileInfo.sizeBytes // empty' <<<"$version_json")"

    [[ -n "$expected_hash" && -n "$expected_size" ]] ||
        fail "No verifiable Hangar-hosted download found for '$slug' $target_version"

    [[ -n "$file_name" ]] || file_name="$slug.jar"

    local target temporary
    target="$plugins_dir/$file_name"
    temporary="$(mktemp "$plugins_dir/.hangar-download.XXXXXX")"
    trap 'rm -f "$temporary"' EXIT INT TERM

    printf 'Downloading %s %s\n' "$slug" "$target_version"

    [[ -n "$HANGAR_JWT" ]] || hangar_authenticate

    curl \
        --fail \
        --silent \
        --show-error \
        --location \
        --retry 3 \
        --header "User-Agent: $USER_AGENT" \
        --header "Authorization: HangarAuth $HANGAR_JWT" \
        --output "$temporary" \
        "$HANGAR_API/projects/$slug/versions/$target_version/PAPER/download" ||
        fail "Download failed for '$slug' $target_version"

    local actual_size
    actual_size="$(wc -c <"$temporary" | tr -d '[:space:]')"

    [[ "$actual_size" == "$expected_size" ]] ||
        fail "'$slug' $target_version has the wrong size"

    local actual_hash
    actual_hash="$(shasum -a 256 "$temporary" | awk '{print $1}')"

    [[ "$actual_hash" == "$expected_hash" ]] ||
        fail "'$slug' $target_version SHA-256 verification failed"

    mv "$temporary" "$target"
    trap - EXIT INT TERM

    local version_id channel_name
    version_id="$(jq -r '.id // empty' <<<"$version_json")"
    [[ -n "$version_id" ]] || version_id=null
    channel_name="$(jq -r '.channel.name // empty' <<<"$version_json")"

    write_installed_version "$server_dir" "hangar" "$slug" "$(
        jq -n \
            --arg version_name "$target_version" \
            --argjson version_id "$version_id" \
            --arg channel_name "$channel_name" \
            --arg sha256 "$expected_hash" \
            --argjson size "$expected_size" \
            --arg file "$file_name" \
            '{
                version_name: $version_name,
                version_id: $version_id,
                channel_name: $channel_name,
                sha256: $sha256,
                size: $size,
                file: $file,
                external: false
            }'
    )"

    printf 'Installed %s %s as %s\n' "$slug" "$target_version" "$target"
    printf 'SHA-256: %s\n' "$expected_hash"
}

# Self-description read back by router.sh -- see the contract note above.
ADAPTER_SOURCE_NAME=hangar
ADAPTER_ENTRY_FUNCTION=process_hangar_plugin
