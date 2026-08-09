# Trusted-source handling for externally-hosted plugin downloads.
#
# Sourced (not exec'd) into plugin.sh's process, after redirect.sh and
# before commands.sh (commands.sh's cmd_add threads the --trust flag it
# parses into route_plugin(), and route_plugin() forwards it on to
# whichever adapter's external-hosting gate needs it -- see router.sh's
# route_plugin() and ../adapter/plugin/hangar.sh's/spiget.sh's external
# gates for the call sites).
#
# What this solves: try_resolve_external_url() (redirect.sh) only handles
# an external URL that belongs to a source Jarlet already has a working
# adapter for. Plenty of external hosts (e.g. Geyser's own
# download.geysermc.org) are neither GitHub, Spiget, nor Hangar -- for
# those, an adapter's external-hosting gate falls through to this file as
# a second, different kind of fallback: not "recognize this URL as another
# adapter", but "has the user explicitly said they trust this domain
# enough to fetch a plain, unverified-by-any-adapter file from it".
#
# Trust is domain-level (not exact-URL, not prefix) and persisted globally
# (not per-server) in a flat file, one domain per line, next to
# jarlet-sys.conf: $SCRIPT_DIR/../$(sys_config_value TRUSTED_SOURCES_FILENAME).
# Domain-level was chosen over exact-URL because the concrete motivating
# case (Geyser's .../versions/latest/builds/latest/downloads/spigot) is
# itself a versionless "latest" API path, not a specific file -- pinning
# trust to that exact string would be trust in name only, since the actual
# bytes behind it change over time regardless.
#
# UX mirrors start.sh's --accept-eula gate: a safety gate that requires an
# explicit opt-in flag, and whose own skip/refusal message tells the user
# exactly what to pass to proceed. Concretely:
#   - `plugin.sh <name> add <identifier> ... --trust` /
#     `plugin.sh <name> update [<identifier>] --trust` -- when an external-
#     hosting gate is hit during that invocation and the domain is not yet
#     trusted, --trust downloads it anyway AND persists the domain to the
#     trust file, exactly like --accept-eula both proceeding past the EULA
#     gate once and writing eula.txt so future runs never need the flag
#     again for that domain.
#   - Once a domain is in the trust file (via --trust, or by hand-editing
#     the file directly -- it's just a flat file, nothing stops that), it
#     is trusted on every future invocation with no flag needed.
#
# May assume plugin.sh has already defined: fail(), sys_config_value(),
# $SCRIPT_DIR, $USER_AGENT, and that curl/shasum are already confirmed on
# PATH by whichever adapter calls into this file (this file itself has no
# plugins it manages directly, so it checks no dependencies of its own at
# source time -- same reasoning store.sh/router.sh give for staying
# dependency-check-free).

# Prints the path to the global trust list file (not guaranteed to exist
# yet -- callers that read it must handle that; trust_domain() below is the
# only writer and creates it on first use).
trusted_sources_file() {
    printf '%s/../%s' "$SCRIPT_DIR" "$(sys_config_value TRUSTED_SOURCES_FILENAME)"
}

# Extracts the host (domain[:port] stripped of any port) from a URL, e.g.
# "https://download.geysermc.org/v2/..." -> "download.geysermc.org". Plain
# parameter expansion, not a curl/awk round trip -- this only ever runs
# against an externalUrl an adapter already fetched over HTTPS, so a
# lightweight scheme/path/port strip is all that's needed.
url_domain() {
    local url="$1"
    local rest="${url#*://}"
    rest="${rest%%/*}"
    rest="${rest%%:*}"
    printf '%s' "$rest"
}

# True (0) if `domain` is present as its own line in the trust file
# (blank lines and #-comments ignored, exact match only -- no wildcarding,
# consistent with the domain-level-not-prefix design above).
is_domain_trusted() {
    local domain="$1"
    local file
    file="$(trusted_sources_file)"

    [[ -f "$file" ]] || return 1

    awk -v d="$domain" '
        $0 !~ /^[[:space:]]*#/ && $0 !~ /^[[:space:]]*$/ {
            line = $0
            gsub(/^[[:space:]]+|[[:space:]]+$/, "", line)
            if (line == d) { found = 1; exit }
        }
        END { exit !found }
    ' "$file"
}

# Appends `domain` to the trust file if not already present. Creates the
# file (with a short header comment) on first use -- this is the only
# writer of this file via the CLI path (--trust); hand-editing it directly
# also works since it's read back with plain awk, same as jarlet-sys.conf.
trust_domain() {
    local domain="$1"
    local file
    file="$(trusted_sources_file)"

    if is_domain_trusted "$domain"; then
        return 0
    fi

    if [[ ! -f "$file" ]]; then
        {
            printf '# jarlet-trusted-sources.conf\n'
            printf '# Domains Jarlet will download plugin jars from directly (plain curl, no\n'
            printf '# adapter, best-effort verification only) when an adapter reports a plugin\n'
            printf '# as hosted externally at a URL on one of these domains. One domain per\n'
            printf '# line. Populated by `plugin.sh <name> add|update ... --trust`; hand-edit is\n'
            printf '# also fine, this is just a flat allowlist.\n'
        } >"$file"
    fi

    printf '%s\n' "$domain" >>"$file"
}

# The core trust-fallback gate, called by an adapter's external-hosting
# check after try_resolve_external_url() has already failed to recognize
# the URL as belonging to a known adapter. Handles all three outcomes
# itself (already trusted -> download; --trust passed -> trust once, then
# download; neither -> print the skip message) so hangar.sh/spiget.sh only
# need one call site each instead of duplicating this branching.
#
# Params: server_dir plugins_dir source id label external_url
#         expected_hash expected_size fallback_filename trust_requested
#         [version_name] [channel_name]
#   - source/id: the declaring adapter's own source/id (e.g. "hangar"/
#     "Geyser"), used only for the skip/success messages and to namespace
#     the plugins-state.json entry -- this file has no source-specific
#     logic of its own.
#   - label: human-readable name to print (falls back to id when a
#     source has no separate display name, mirroring hangar.sh/spiget.sh's
#     own "${name:-$id}" convention).
#   - expected_hash/expected_size: optional. Almost always empty in
#     practice -- confirmed live that Hangar's own downloads.PAPER object
#     has fileInfo=null whenever externalUrl is set (mutually exclusive on
#     Geyser's real data), so there is no Hangar-provided checksum to fall
#     back on for the motivating case. Still honored here, in case a
#     future Hangar project (or another adapter) ever does report both, or
#     an adapter passes its own out-of-band checksum -- see the task note
#     this was built from ("checksum still available e.g. through hangar").
#   - fallback_filename: used when the download has no usable
#     Content-Disposition filename.
#   - trust_requested: "true"/"false" (string), threaded down from the
#     --trust CLI flag through route_plugin()/the adapter entry function.
#   - version_name/channel_name: OPTIONAL. External hosting only ever
#     means "the bytes live off-adapter" -- it says nothing about whether
#     the adapter still resolved real version metadata before discovering
#     that. Hangar is the concrete case: process_hangar_plugin() already
#     calls /latest?channel=... (or resolves a pin) and fetches the full
#     version object *before* it ever looks at downloads.PAPER.externalUrl,
#     so by the time it reaches this function it has a real, comparable
#     target_version and channel.name -- only the download mechanism
#     differs (trusted curl instead of the authenticated Hangar download
#     endpoint), not the versioning. Callers with that context MUST pass it
#     through here so it lands in plugins-state.json and the adapter's own
#     "already up to date" check (e.g. hangar.sh's read_installed_version
#     comparison, which runs before the externalUrl branch is even reached)
#     works the same for a trusted external download as for any other.
#     Callers with genuinely no version identity at this point (Spiget's
#     external branch fires immediately off resource_json, before any
#     version is resolved -- there is nothing to pass) simply omit these,
#     and get the literal "external"/null fallback documented at the
#     write_installed_version() call below.
#
# Returns 0 in every case (matches hangar.sh/spiget.sh's existing "skip is
# not a failure" convention) -- callers should `return $?` straight after
# calling this, exactly like they already do for the redirect.sh case.
#
# No "already installed, skip re-fetch" check is done here on purpose --
# that check belongs to (and, for callers that pass real version_name, is
# already performed by) the calling adapter itself before it ever reaches
# the externalUrl branch, exactly like hangar.sh's target_version
# comparison above its externalUrl check. This function only decides
# whether it's allowed to fetch, and fetches unconditionally once it
# decides yes.
handle_untrusted_external_url() {
    local server_dir="$1" plugins_dir="$2" source="$3" id="$4" label="$5"
    local external_url="$6" expected_hash="$7" expected_size="$8"
    local fallback_filename="$9" trust_requested="${10}"
    local version_name="${11:-external}" channel_name="${12:-}"

    local domain
    domain="$(url_domain "$external_url")"

    if is_domain_trusted "$domain"; then
        printf '"%s" is hosted externally at %s -- domain "%s" is trusted, downloading directly\n' \
            "$label" "$external_url" "$domain"
    elif [[ "$trust_requested" == "true" ]]; then
        trust_domain "$domain"
        printf '"%s" is hosted externally at %s -- trusting domain "%s" (saved to %s) and downloading directly\n' \
            "$label" "$external_url" "$domain" "$(trusted_sources_file)"
    else
        printf 'Skipping "%s": hosted externally, install manually: %s\n' "$label" "$external_url"
        printf 'Or re-run this command with --trust to trust the "%s" domain and download it directly (best-effort verification only -- see %s)\n' \
            "$domain" "$(trusted_sources_file)"
        return 0
    fi

    command -v curl >/dev/null || fail "curl is required to fetch a trusted external download"
    command -v shasum >/dev/null || fail "shasum is required to fetch a trusted external download"

    local target temporary header_file
    temporary="$(mktemp "$plugins_dir/.trust-download.XXXXXX")"
    header_file="$(mktemp "$plugins_dir/.trust-headers.XXXXXX")"
    trap 'rm -f "$temporary" "$header_file"' EXIT INT TERM

    printf 'Downloading %s from %s\n' "$label" "$external_url"

    curl \
        --fail \
        --silent \
        --show-error \
        --location \
        --retry 3 \
        --header "User-Agent: $USER_AGENT" \
        --dump-header "$header_file" \
        --output "$temporary" \
        "$external_url" ||
        fail "Download failed for '$label' from $external_url"

    local actual_size
    actual_size="$(wc -c <"$temporary" | tr -d '[:space:]')"

    [[ "$actual_size" -gt 0 ]] ||
        fail "'$label' downloaded as an empty file from $external_url"

    local verified_hash=""

    if [[ -n "$expected_hash" ]]; then
        # A real checksum was available despite the external hosting (see
        # the params doc comment above) -- verify it for real, same as any
        # adapter-hosted download.
        local actual_hash
        actual_hash="$(shasum -a 256 "$temporary" | awk '{print $1}')"

        [[ "$actual_hash" == "$expected_hash" ]] ||
            fail "'$label' SHA-256 verification failed for external download from $external_url"

        verified_hash="$expected_hash"
        printf 'SHA-256 verified (checksum was available from %s despite external hosting): %s\n' "$source" "$verified_hash"
    elif [[ -n "$expected_size" ]] && [[ "$expected_size" -gt 0 ]] 2>/dev/null; then
        if [[ "$actual_size" != "$expected_size" ]]; then
            printf 'Warning: "%s" downloaded size (%s bytes) does not match the expected size (%s bytes) -- no cryptographic checksum was available to verify further, proceeding anyway since this domain is trusted\n' \
                "$label" "$actual_size" "$expected_size"
        fi
    else
        printf 'Warning: no checksum or size is available to verify this trusted external download -- "%s" was fetched as-is from %s with no cryptographic verification\n' \
            "$label" "$external_url"
    fi

    local file_name
    file_name="$(grep -i '^content-disposition:' "$header_file" | sed -E 's/.*filename="?([^"'\'';]+)"?.*/\1/i' | tr -d '\r' | tail -n1)"
    [[ -n "$file_name" ]] || file_name="$fallback_filename"

    target="$plugins_dir/$file_name"
    mv "$temporary" "$target"
    trap - EXIT INT TERM
    rm -f "$header_file"

    # version_name: real version identity when the caller has one to give
    # (see the version_name/channel_name param doc above -- Hangar's is the
    # motivating case), otherwise the fixed literal "external" (not a
    # null/empty value), which is deliberate for callers with no version
    # context at all: it guarantees read_installed_version() never
    # coincidentally matches some future value, so a caller with no real
    # identity to compare against always re-downloads on every explicit
    # invocation rather than silently short-circuiting on an identity this
    # file has no way to trust. A fixed non-null literal (rather than null)
    # also keeps list.sh's existing `version_name // empty` display showing
    # a real (if generic) value instead of misreporting an actually-
    # installed trusted jar as "not installed".
    write_installed_version "$server_dir" "$source" "$id" "$(
        jq -n \
            --argjson size "$actual_size" \
            --arg file "$file_name" \
            --arg sha256 "$verified_hash" \
            --arg version_name "$version_name" \
            --arg channel_name "$channel_name" \
            '{
                version_name: $version_name,
                version_id: null,
                channel_name: (if $channel_name == "" then null else $channel_name end),
                sha256: (if $sha256 == "" then null else $sha256 end),
                size: $size,
                file: $file,
                external: true
            }'
    )"

    printf 'Installed %s as %s (trusted external download, external=true)\n' "$label" "$target"
}
