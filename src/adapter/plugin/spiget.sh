# Spiget (SpigotMC) plugin source adapter.
#
# Sourced (not exec'd) into plugins.sh's process by router.sh's
# route_plugin(), lazily and only once, the first time a declared plugin
# entry has source = "spiget". See ../plugin/hangar.sh's header for the
# full adapter contract this file must satisfy; summarized here:
#   - Self-describing: sets ADAPTER_SOURCE_NAME=spiget and
#     ADAPTER_ENTRY_FUNCTION=process_spiget_plugin at the bottom of this
#     file, both read back by router.sh.
#   - Entry point signature: process_spiget_plugin(server_dir, plugins_dir,
#     id, policy_json).
#   - May assume plugins.sh has already defined: fail(), config_value(),
#     sys_config_value(), $SCRIPT_DIR, $USER_AGENT, plugin_state_file(),
#     read_installed_version(), write_installed_version() (all from
#     store.sh), and that jq/dasel are already confirmed to be on PATH.
#   - Must check its own dependencies (curl) at source time.
#
# Spiget (https://api.spiget.org/v2) needs no API key/auth (confirmed live
# against the real API), unlike Hangar -- so unlike hangar.sh there is no
# authenticate/JWT machinery here at all.
#
# Two real gaps versus hangar.sh/paper.sh, both confirmed by live probes
# against the real API (not just the research doc) before writing this:
#
#   1. No checksum of any kind is exposed anywhere in Spiget's API (no
#      SHA-256, no digest, nothing). Verification here is size-only, and
#      even that is best-effort: only the *resource*-level `file.size` is
#      ever populated (in KB/MB/GB, not bytes), and per the research doc it
#      isn't guaranteed to match any specific version's file -- it's also
#      simply 0/unset for many resources (e.g. externally-hosted ones).
#      A clear warning is always printed that no cryptographic verification
#      was possible, every time, not just when the size check is skipped.
#
#   2. The research doc assumed a version's `uuid` could be used directly
#      in place of its numeric `id` in the /versions/{version} and
#      /versions/{version}/download(/proxy) paths ("or the literal
#      latest"). Live probes (and the swagger spec itself, which types
#      `version` as "Version ID or 'latest'") show this is NOT true: only
#      the numeric id (or the literal "latest") resolves; a uuid in that
#      position 404s. uuid remains the right thing to *persist* and compare
#      against (per the doc: authoritative, unlike the "deprecated" id) --
#      it's just not directly queryable, so resolving a pinned uuid back to
#      a numeric id requires a bounded scan of the versions list (see
#      spiget_resolve_pinned_version() below). This is a real, worth-noting
#      divergence from the research doc, not a silent workaround.
#
# Also confirmed live: the plain (non-proxy) /download endpoint redirects
# to a spigotmc.org HTML resource page, not a raw file (SpigotMC requires a
# browser click-through) -- so this adapter always uses .../download/proxy,
# which does return the raw file directly (confirmed: 200,
# content-type: application/octet-stream, with a usable
# Content-Disposition filename). The proxy endpoint's documented "pretty
# strict rate-limit" is why this adapter, like hangar.sh, only ever
# downloads once a version-uuid mismatch has already been established by a
# cheap metadata-only check.

command -v curl >/dev/null || fail "curl is required for the spiget source"

readonly SPIGET_API="$(sys_config_value SPIGET_API)"

# Plain GET against $SPIGET_API$path, returning the response body. No auth
# header of any kind -- confirmed live that Spiget's API is fully anonymous.
spiget_get() {
    local path="$1"
    local raw status body

    raw="$(
        curl \
            --silent \
            --show-error \
            --location \
            --header "User-Agent: $USER_AGENT" \
            --write-out '\n%{http_code}' \
            "$SPIGET_API$path"
    )" || fail "Spiget request failed: $path"

    status="${raw##*$'\n'}"
    body="${raw%$'\n'*}"

    [[ "$status" == 2* ]] ||
        fail "Spiget request to $path failed with HTTP $status"

    printf '%s' "$body"
}

# Resolves a pinned version_uuid to its full ResourceVersion metadata.
# /versions/{version} only accepts a numeric id (confirmed live + swagger),
# not a uuid, so a pinned uuid has to be found by scanning the versions
# list instead of a single direct lookup. Bounded to a few hundred most
# recent versions (5 pages of 100, newest first) -- if a pin is older than
# that, this fails with a clear message rather than scanning indefinitely
# against a rate-limited API.
spiget_resolve_pinned_version() {
    local id="$1" pin="$2"
    local page found=""

    for page in 1 2 3 4 5; do
        local page_json match
        page_json="$(spiget_get "/resources/$id/versions?size=100&page=$page&sort=-releaseDate")" ||
            fail "Could not list versions for '$id' while resolving pin '$pin'"

        match="$(jq -c --arg uuid "$pin" '[.[] | select(.uuid == $uuid)] | first // empty' <<<"$page_json")"

        if [[ -n "$match" ]]; then
            found="$match"
            break
        fi

        # Fewer than a full page means we've reached the end of the list.
        [[ "$(jq 'length' <<<"$page_json")" == "100" ]] || break
    done

    [[ -n "$found" ]] ||
        fail "Could not find pinned Spiget version '$pin' for resource '$id' (searched up to 500 most recent versions)"

    printf '%s' "$found"
}

# Converts a Spiget resource-level file.size (a float in file.sizeUnit) to
# an approximate byte count. Best-effort only -- see the "No checksum"
# warning this feeds into. Assumes 1024-based units (KB/MB/GB), the common
# convention; there is no authoritative spec for which base Spiget itself
# uses.
spiget_size_to_bytes() {
    local size="$1" unit="$2"
    local multiplier=1
    local unit_upper

    unit_upper="$(printf '%s' "$unit" | tr '[:lower:]' '[:upper:]')"

    case "$unit_upper" in
        "" | B) multiplier=1 ;;
        KB) multiplier=1024 ;;
        MB) multiplier=$((1024 * 1024)) ;;
        GB) multiplier=$((1024 * 1024 * 1024)) ;;
        *) printf ''; return 0 ;;
    esac

    awk -v s="$size" -v m="$multiplier" 'BEGIN { printf "%.0f", s * m }'
}

# Sanitizes an arbitrary Spiget resource name into a safe jar filename
# component (mirrors hangar.sh's "$slug.jar" fallback, but spiget's id is
# purely numeric and not human-readable, so the resource name is preferred
# when available).
spiget_sanitize_filename() {
    local name="$1"
    local sanitized

    sanitized="$(printf '%s' "$name" | tr -c '[:alnum:]._-' '-' | tr '[:upper:]' '[:lower:]')"
    sanitized="$(printf '%s' "$sanitized" | sed -E 's/-+/-/g; s/^-+//; s/-+$//')"

    printf '%s' "$sanitized"
}

process_spiget_plugin() {
    local server_dir="$1" plugins_dir="$2" id="$3" policy_json="$4"

    [[ "$id" =~ ^[0-9]+$ ]] ||
        fail "Invalid Spiget resource id: $id (must be numeric)"

    local resource_json external premium name resource_size resource_unit
    resource_json="$(spiget_get "/resources/$id")" ||
        fail "Could not fetch Spiget resource '$id'"

    external="$(jq -r '.external // false' <<<"$resource_json")"
    premium="$(jq -r '.premium // false' <<<"$resource_json")"
    name="$(jq -r '.name // empty' <<<"$resource_json")"
    resource_size="$(jq -r '.file.size // 0' <<<"$resource_json")"
    resource_unit="$(jq -r '.file.sizeUnit // empty' <<<"$resource_json")"

    if [[ "$external" == "true" ]]; then
        printf 'Skipping "%s" (%s): resource is hosted externally, install manually\n' "${name:-$id}" "$id"
        return 0
    fi

    if [[ "$premium" == "true" ]]; then
        printf 'Skipping "%s" (%s): resource is premium/paid, install manually\n' "${name:-$id}" "$id"
        return 0
    fi

    local pin target_version_json
    pin="$(jq -r '.pin // empty' <<<"$policy_json")"

    if [[ -n "$pin" ]]; then
        if [[ "$pin" =~ ^[0-9]+$ ]]; then
            # A plain numeric version id was given directly -- resolvable
            # in one call.
            target_version_json="$(spiget_get "/resources/$id/versions/$pin")" ||
                fail "Could not fetch pinned version '$pin' for '$id'"
        else
            # Treat as a version_uuid (the documented pin shape) -- needs
            # the bounded scan since /versions/{uuid} isn't queryable
            # directly (see header comment).
            target_version_json="$(spiget_resolve_pinned_version "$id" "$pin")"
        fi
    else
        target_version_json="$(spiget_get "/resources/$id/versions/latest")" ||
            fail "Could not resolve latest version for '$id'"
    fi

    local target_uuid target_version_id target_name target_release
    target_uuid="$(jq -r '.uuid // empty' <<<"$target_version_json")"
    target_version_id="$(jq -r '.id // empty' <<<"$target_version_json")"
    target_name="$(jq -r '.name // empty' <<<"$target_version_json")"
    target_release="$(jq -r '.releaseDate // empty' <<<"$target_version_json")"

    [[ -n "$target_uuid" && -n "$target_version_id" ]] ||
        fail "Spiget returned no usable version for '$id'"

    [[ -n "$target_release" ]] || target_release=null

    # version_name is the field store.sh's read_installed_version() reads
    # back for comparison -- uuid (not the human-readable name, and not the
    # "deprecated" numeric id) is what's authoritative per the research
    # doc, so it's what's stored there, same role target_version plays in
    # hangar.sh.
    local installed
    installed="$(read_installed_version "$server_dir" "spiget" "$id")"

    if [[ "$installed" == "$target_uuid" ]]; then
        printf '"%s" is already up to date (%s)\n' "${name:-$id}" "${target_name:-$target_uuid}"
        return 0
    fi

    local file_name sanitized
    sanitized="$(spiget_sanitize_filename "$name")"
    [[ -n "$sanitized" ]] || sanitized="spiget-$id"
    file_name="$sanitized.jar"

    local target temporary header_file
    target="$plugins_dir/$file_name"
    temporary="$(mktemp "$plugins_dir/.spiget-download.XXXXXX")"
    header_file="$(mktemp "$plugins_dir/.spiget-headers.XXXXXX")"
    trap 'rm -f "$temporary" "$header_file"' EXIT INT TERM

    printf 'Downloading %s %s\n' "${name:-$id}" "${target_name:-$target_uuid}"

    # Always the proxy endpoint -- the plain /download redirects to a
    # spigotmc.org HTML page, not a raw file (confirmed live; see header
    # comment). Rate-limited per Spiget's own docs, which is why this only
    # runs after the cheap metadata check above already found a mismatch.
    curl \
        --fail \
        --silent \
        --show-error \
        --location \
        --retry 3 \
        --header "User-Agent: $USER_AGENT" \
        --dump-header "$header_file" \
        --output "$temporary" \
        "$SPIGET_API/resources/$id/versions/$target_version_id/download/proxy" ||
        fail "Download failed for '$id' ${target_name:-$target_uuid}"

    local actual_size
    actual_size="$(wc -c <"$temporary" | tr -d '[:space:]')"

    [[ "$actual_size" -gt 0 ]] ||
        fail "'$id' ${target_name:-$target_uuid} downloaded as an empty file"

    # Best-effort, weak size check -- Spiget exposes no checksum at all (see
    # header comment). Only applied when the resource actually reports a
    # size; otherwise skipped outright rather than failing on missing data.
    if [[ -n "$resource_unit" ]] && awk -v s="$resource_size" 'BEGIN { exit !(s > 0) }'; then
        local expected_bytes
        expected_bytes="$(spiget_size_to_bytes "$resource_size" "$resource_unit")"

        if [[ -n "$expected_bytes" ]] && ! awk -v a="$actual_size" -v e="$expected_bytes" \
            'BEGIN { diff = a - e; if (diff < 0) diff = -diff; exit !(diff <= (e * 0.5)) }'; then
            fail "'$id' ${target_name:-$target_uuid} downloaded size ($actual_size bytes) is wildly different from Spiget's reported size (~$expected_bytes bytes)"
        fi
    fi

    printf 'Warning: Spiget exposes no checksum for any plugin -- "%s" %s was only verified by file size, not cryptographically\n' \
        "${name:-$id}" "${target_name:-$target_uuid}"

    # Prefer the real filename the proxy reports over the sanitized guess,
    # when present.
    local disposition_name
    disposition_name="$(grep -i '^content-disposition:' "$header_file" | sed -E 's/.*filename="?([^"'\'';]+)"?.*/\1/i' | tr -d '\r' | tail -n1)"

    if [[ -n "$disposition_name" ]]; then
        file_name="$disposition_name"
        target="$plugins_dir/$file_name"
    fi

    mv "$temporary" "$target"
    trap - EXIT INT TERM
    rm -f "$header_file"

    write_installed_version "$server_dir" "spiget" "$id" "$(
        jq -n \
            --arg version_name "$target_uuid" \
            --argjson version_id "$target_version_id" \
            --arg version_label "$target_name" \
            --argjson release_date "$target_release" \
            --argjson size "$actual_size" \
            --arg file "$file_name" \
            '{
                version_name: $version_name,
                version_id: $version_id,
                version_label: $version_label,
                release_date: $release_date,
                sha256: null,
                size: $size,
                file: $file,
                external: false
            }'
    )"

    printf 'Installed %s %s as %s\n' "${name:-$id}" "${target_name:-$target_uuid}" "$target"
    printf 'No cryptographic checksum available for this source (size-verified only)\n'
}

# Self-description read back by router.sh -- see the contract note above.
ADAPTER_SOURCE_NAME=spiget
ADAPTER_ENTRY_FUNCTION=process_spiget_plugin
