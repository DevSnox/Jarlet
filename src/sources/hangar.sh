# Hangar plugin source adapter.
#
# Sourced (not exec'd) into plugins.sh's process by main()'s dispatch loop,
# lazily and only once, the first time a declared plugin entry has
# source = "hangar". This keeps HANGAR_JWT process/env-scoped without
# inventing an inter-process protocol for passing auth state back, and
# avoids resolving HANGAR_API or requiring curl/shasum for templates that
# declare zero hangar plugins.
#
# Contract for future source adapters (spiget.sh, github-releases.sh):
#   - Entry point: a function process_<source>_plugin(server_dir,
#     plugins_dir, id, policy_json), called from an explicit
#     `case "$source" in hangar) ... ;; spiget) ... ;; esac` in plugins.sh's
#     main(). Explicit per-source function names + an explicit case
#     statement, not a naming-convention-based dispatch.
#   - May assume plugins.sh has already defined: fail(), config_value(),
#     sys_config_value(), $SCRIPT_DIR, $USER_AGENT, plugin_state_file(),
#     read_installed_version(), write_installed_version(), and that jq/dasel
#     are already confirmed to be on PATH.
#   - Must check any dependencies of its own (curl, shasum, ...) before use;
#     plugins.sh does not check them unconditionally on its behalf.
#   - Must keep any credentials/tokens process/env-scoped only, never
#     written to disk — see HANGAR_JWT below.

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

    if [[ -n "$review_state" && "$review_state" != "APPROVED" ]]; then
        printf 'Skipping "%s" %s: review state is "%s", not approved\n' "$slug" "$target_version" "$review_state"
        return 0
    fi

    local external_url
    external_url="$(jq -r '.downloads.PAPER.externalUrl // empty' <<<"$version_json")"

    if [[ -n "$external_url" ]]; then
        printf 'Skipping "%s" %s: hosted externally, install manually: %s\n' \
            "$slug" "$target_version" "$external_url"
        return 0
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
