#!/usr/bin/env bash
set -euo pipefail

readonly SCRIPT_DIR="$(
    CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd
)"

readonly SYS_CONFIG_FILE="$SCRIPT_DIR/jarlet-sys.conf"

fail() {
    printf 'Error: %s\n' "$1" >&2
    exit 1
}

config_value() {
    local key="$1"
    local file="$2"

    awk -F= -v key="$key" '
        $0 !~ /^[[:space:]]*#/ && $1 == key {
            sub(/^[^=]*=/, "")
            print
            exit
        }
    ' "$file"
}

sys_config_value() {
    local key="$1"

    [[ -f "$SYS_CONFIG_FILE" ]] ||
        fail "$SYS_CONFIG_FILE does not exist"

    local value
    value="$(config_value "$key" "$SYS_CONFIG_FILE")"

    [[ -n "$value" ]] ||
        fail "Missing required key '$key' in $SYS_CONFIG_FILE"

    printf '%s' "$value"
}

servers_root() {
    if [[ -n "${JARLET_SERVERS_DIR:-}" ]]; then
        [[ "$JARLET_SERVERS_DIR" = /* ]] ||
            fail "JARLET_SERVERS_DIR must be an absolute path"
        printf '%s' "$JARLET_SERVERS_DIR"
    else
        local default
        default="$(sys_config_value SERVERS_DIR_DEFAULT)"
        printf '%s' "${default/\$HOME/$HOME}"
    fi
}

readonly HANGAR_API="$(sys_config_value HANGAR_API)"
readonly PROJECT_NAME="$(sys_config_value PROJECT_NAME)"
readonly REPO_URL="$(sys_config_value REPO_URL)"

readonly VERSION_SCRIPT="$SCRIPT_DIR/version.sh"
[[ -x "$VERSION_SCRIPT" ]] ||
    fail "$VERSION_SCRIPT does not exist or is not executable"

JARLET_VERSION="$("$VERSION_SCRIPT")" ||
    fail "Could not determine jarlet version"
readonly JARLET_VERSION

readonly USER_AGENT="${PROJECT_NAME}/${JARLET_VERSION} (${REPO_URL})"

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

# Converts a jarlet.toml file to JSON so the rest of this script can use jq
# throughout, the same way install.sh does for the Paper API's JSON.
toml_to_json() {
    dasel --file "$1" --read toml --write json --pretty=false '.'
}

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

main() {
    if (( $# < 1 )); then
        printf 'Usage: %s <name>\n' "$0" >&2
        exit 2
    fi

    local name="$1"

    [[ "$name" =~ ^[0-9A-Za-z._-]+$ ]] ||
        fail "Server name must be a simple name (letters, digits, ._-)"

    command -v curl >/dev/null || fail "curl is required"
    command -v jq >/dev/null || fail "jq is required"
    command -v shasum >/dev/null || fail "shasum is required"
    command -v dasel >/dev/null ||
        fail "dasel is required to read jarlet.toml (e.g. 'brew install dasel')"

    local root server_dir toml_file
    root="$(servers_root)"
    server_dir="$root/$name"

    [[ -d "$server_dir" ]] ||
        fail "No server named '$name' found at $server_dir"

    toml_file="$server_dir/jarlet.toml"

    [[ -f "$toml_file" ]] ||
        fail "$toml_file does not exist. plugins.sh reads the plugin list from a per-server jarlet.toml (see prototyping/documentation/concepts/server-templating); setup.sh does not generate one yet, so place one in the server directory manually for now."

    local plugins_json
    plugins_json="$(toml_to_json "$toml_file")" ||
        fail "Could not parse $toml_file as TOML"

    local plugins_dir
    plugins_dir="$server_dir/plugins"
    mkdir -p "$plugins_dir"

    local count
    count="$(jq '(.plugins // []) | length' <<<"$plugins_json")"

    if (( count == 0 )); then
        printf 'No plugins declared in %s\n' "$toml_file"
        return 0
    fi

    local i entry source id policy_json
    for (( i = 0; i < count; i++ )); do
        entry="$(jq -c ".plugins[$i]" <<<"$plugins_json")"
        source="$(jq -r '.source' <<<"$entry")"
        id="$(jq -r '.id' <<<"$entry")"
        policy_json="$(jq -c '.policy' <<<"$entry")"

        if [[ "$source" != "hangar" ]]; then
            printf 'Skipping "%s" (%s): only the hangar source is implemented\n' "$id" "$source"
            continue
        fi

        process_hangar_plugin "$server_dir" "$plugins_dir" "$id" "$policy_json"
    done
}

main "$@"
