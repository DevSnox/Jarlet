# GitHub Releases plugin source adapter.
#
# Sourced (not exec'd) into plugins.sh's process by router.sh's
# route_plugin(), lazily and only once, the first time a declared plugin
# entry has source = "github-releases" -- whether that routing came from
# the update-all loop, a targeted `update <source> <id>`, or add's
# immediate post-declare fetch. See ../plugin/hangar.sh's header for the
# full adapter contract this file follows; not repeated in full here.
#
# Unlike Spiget/Hangar, GitHub has no discovery layer and no channel
# concept -- see prototyping/documentation/sources/github-releases-plugin-fetching.md
# for the full research this adapter implements. Summary relevant to this
# file:
#   - id is "owner/repo" (e.g. "ViaVersion/ViaVersion"), not a searchable
#     slug/resource id -- the caller must already know it.
#   - policy is { pin = "<tag_name>" } or { track = "latest" } (no
#     "channel" field -- GitHub's own /releases/latest already excludes
#     prereleases/drafts).
#   - A release's assets array has no "this is the plugin jar" field, so
#     asset selection is a deterministic filter (content-type + name-
#     pattern exclusion, see github_releases_pick_asset() below); if more
#     than one candidate survives (or zero), this adapter skips with a
#     message rather than guessing or prompting interactively -- there is
#     no mechanism in this codebase yet to persist a resolved asset choice
#     back into jarlet.toml, so a one-time interactive disambiguation
#     would have nowhere durable to be remembered.
#   - GitHub's per-asset `digest` (sha256:...) is verified when present;
#     falls back to size-only verification (with a printed warning) when
#     absent, same "best available verification" precedent as hangar.sh.

command -v curl >/dev/null || fail "curl is required for the github-releases source"
command -v shasum >/dev/null || fail "shasum is required for the github-releases source"

readonly GITHUB_API="$(sys_config_value GITHUB_API)"

# GET against $GITHUB_API$path, returning the response body. Sends an
# Authorization header only if JARLET_GITHUB_TOKEN is set in the
# environment (raises the unauthenticated 60 req/hr limit to 5000 req/hr
# when supplied) -- never written to disk, process/env-scoped only, same
# treatment hangar.sh gives its JWT.
github_releases_get() {
    local path="$1"
    local raw status body

    if [[ -n "${JARLET_GITHUB_TOKEN:-}" ]]; then
        raw="$(
            curl \
                --silent \
                --show-error \
                --location \
                --header "User-Agent: $USER_AGENT" \
                --header "Authorization: Bearer $JARLET_GITHUB_TOKEN" \
                --header "Accept: application/vnd.github+json" \
                --write-out '\n%{http_code}' \
                "$GITHUB_API$path"
        )" || fail "GitHub request failed: $path"
    else
        raw="$(
            curl \
                --silent \
                --show-error \
                --location \
                --header "User-Agent: $USER_AGENT" \
                --header "Accept: application/vnd.github+json" \
                --write-out '\n%{http_code}' \
                "$GITHUB_API$path"
        )" || fail "GitHub request failed: $path"
    fi

    status="${raw##*$'\n'}"
    body="${raw%$'\n'*}"

    printf '%s\n%s' "$status" "$body"
}

# Given a release's assets array (as JSON), applies the deterministic
# jar-selection filter: prefer plugin-jar-shaped content types, exclude
# known non-plugin name patterns (sources/javadoc jars, checksum/signature
# files, changelogs/text files). Prints the single surviving asset object
# as JSON if exactly one remains; prints nothing (and returns 1) otherwise
# so the caller can distinguish "zero matches" / "ambiguous" from "found".
github_releases_pick_asset() {
    local assets_json="$1"
    local candidates count

    candidates="$(
        jq -c '
            [
                .[]
                | select(
                    (.content_type == "application/java-archive")
                    or (.content_type == "application/zip")
                    or (.content_type == "application/octet-stream")
                )
                | select(
                    (.name | test("-sources\\.jar$"; "i")) or
                    (.name | test("-javadoc\\.jar$"; "i")) or
                    (.name | test("\\.sha256$"; "i")) or
                    (.name | test("\\.asc$"; "i")) or
                    (.name | test("^changelog"; "i")) or
                    (.name | test("\\.txt$"; "i"))
                    | not
                )
            ]
        ' <<<"$assets_json"
    )"

    count="$(jq 'length' <<<"$candidates")"

    if [[ "$count" == "1" ]]; then
        jq -c '.[0]' <<<"$candidates"
        return 0
    fi

    return 1
}

process_github_releases_plugin() {
    local server_dir="$1" plugins_dir="$2" id="$3" policy_json="$4"

    [[ "$id" =~ ^[0-9A-Za-z._-]+/[0-9A-Za-z._-]+$ ]] ||
        fail "Invalid GitHub owner/repo id: $id"

    local pin release_path
    pin="$(jq -r '.pin // empty' <<<"$policy_json")"

    if [[ -n "$pin" ]]; then
        release_path="/repos/$id/releases/tags/$pin"
    else
        release_path="/repos/$id/releases/latest"
    fi

    local status_and_body status release_json
    status_and_body="$(github_releases_get "$release_path")" ||
        fail "Could not reach GitHub for '$id'"
    status="${status_and_body%%$'\n'*}"
    release_json="${status_and_body#*$'\n'}"

    if [[ "$status" == "404" ]]; then
        printf 'Skipping "%s": no matching GitHub release found (repo may not use GitHub Releases for distribution)\n' "$id"
        return 0
    fi

    [[ "$status" == 2* ]] ||
        fail "GitHub request for '$id' failed with HTTP $status"

    local tag_name prerelease draft
    tag_name="$(jq -r '.tag_name // empty' <<<"$release_json")"
    prerelease="$(jq -r '.prerelease // false' <<<"$release_json")"
    draft="$(jq -r '.draft // false' <<<"$release_json")"

    [[ -n "$tag_name" ]] ||
        fail "GitHub returned no tag_name for '$id'"

    if [[ "$prerelease" == "true" || "$draft" == "true" ]]; then
        printf 'Skipping "%s": release %s is a prerelease/draft\n' "$id" "$tag_name"
        return 0
    fi

    local installed
    installed="$(read_installed_version "$server_dir" "github-releases" "$id")"

    if [[ "$installed" == "$tag_name" ]]; then
        printf '"%s" is already up to date (%s)\n' "$id" "$tag_name"
        return 0
    fi

    local assets_json asset_json
    assets_json="$(jq -c '.assets // []' <<<"$release_json")"

    if ! asset_json="$(github_releases_pick_asset "$assets_json")"; then
        local candidate_count
        candidate_count="$(jq 'length' <<<"$assets_json")"
        printf 'Skipping "%s" %s: could not determine a single plugin jar among %s release asset(s); install manually\n' \
            "$id" "$tag_name" "$candidate_count"
        return 0
    fi

    local asset_name asset_size asset_url asset_digest expected_hash
    asset_name="$(jq -r '.name' <<<"$asset_json")"
    asset_size="$(jq -r '.size' <<<"$asset_json")"
    asset_url="$(jq -r '.browser_download_url' <<<"$asset_json")"
    asset_digest="$(jq -r '.digest // empty' <<<"$asset_json")"

    expected_hash=""
    if [[ "$asset_digest" == sha256:* ]]; then
        expected_hash="${asset_digest#sha256:}"
    fi

    [[ -n "$asset_name" && -n "$asset_url" ]] ||
        fail "GitHub release '$id' $tag_name has an unusable asset entry"

    local target temporary
    target="$plugins_dir/$asset_name"
    temporary="$(mktemp "$plugins_dir/.github-releases-download.XXXXXX")"
    trap 'rm -f "$temporary"' EXIT INT TERM

    printf 'Downloading %s %s\n' "$id" "$tag_name"

    if [[ -n "${JARLET_GITHUB_TOKEN:-}" ]]; then
        curl \
            --fail \
            --silent \
            --show-error \
            --location \
            --retry 3 \
            --header "User-Agent: $USER_AGENT" \
            --header "Authorization: Bearer $JARLET_GITHUB_TOKEN" \
            --output "$temporary" \
            "$asset_url" ||
            fail "Download failed for '$id' $tag_name"
    else
        curl \
            --fail \
            --silent \
            --show-error \
            --location \
            --retry 3 \
            --header "User-Agent: $USER_AGENT" \
            --output "$temporary" \
            "$asset_url" ||
            fail "Download failed for '$id' $tag_name"
    fi

    local actual_size
    actual_size="$(wc -c <"$temporary" | tr -d '[:space:]')"

    [[ "$actual_size" == "$asset_size" ]] ||
        fail "'$id' $tag_name has the wrong size"

    if [[ -n "$expected_hash" ]]; then
        local actual_hash
        actual_hash="$(shasum -a 256 "$temporary" | awk '{print $1}')"

        [[ "$actual_hash" == "$expected_hash" ]] ||
            fail "'$id' $tag_name SHA-256 verification failed"
    else
        printf 'Warning: no digest published for "%s" %s asset; verified by size only\n' "$id" "$tag_name"
    fi

    mv "$temporary" "$target"
    trap - EXIT INT TERM

    write_installed_version "$server_dir" "github-releases" "$id" "$(
        jq -n \
            --arg version_name "$tag_name" \
            --arg sha256 "$expected_hash" \
            --argjson size "$asset_size" \
            --arg file "$asset_name" \
            '{
                version_name: $version_name,
                version_id: null,
                channel_name: "",
                sha256: $sha256,
                size: $size,
                file: $file,
                external: false
            }'
    )"

    printf 'Installed %s %s as %s\n' "$id" "$tag_name" "$target"
    if [[ -n "$expected_hash" ]]; then
        printf 'SHA-256: %s\n' "$expected_hash"
    fi
}

# Self-description read back by router.sh -- see the contract note in
# ../plugin/hangar.sh's header for the full adapter contract.
ADAPTER_SOURCE_NAME=github-releases
ADAPTER_ENTRY_FUNCTION=process_github_releases_plugin
