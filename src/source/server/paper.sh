# Paper server-software source adapter.
#
# Sourced (not exec'd) into install.sh's process by its dispatcher,
# lazily and only once, the first time [server].package resolves to
# "paper" -- currently the only legal value, but structured the same way
# plugins.sh's dispatch_plugin() already routes to per-source adapters
# (see src/source/plugin/hangar.sh) so a second server-software adapter
# later is a small addition, not a rewrite.
#
# Contract for future server-software adapters (e.g. a hypothetical
# purpur.sh, folia.sh):
#   - Entry point: a function install_<package>_server(minecraft_version,
#     target), called from an explicit
#     `case "$package" in paper) ... ;; *) fail ... ;; esac` in install.sh's
#     dispatcher. Explicit per-package function names + an explicit case
#     statement, not a naming-convention-based dispatch.
#   - May assume install.sh has already defined: fail(), sys_config_value(),
#     $SCRIPT_DIR, $USER_AGENT.
#   - Must check any dependencies of its own (curl, jq, shasum, ...) before
#     use; install.sh does not check them unconditionally on its behalf.

readonly PAPER_API="$(sys_config_value PAPER_API)"

install_paper_server() {
    local minecraft_version="$1"
    local target="${2:-server.jar}"

    [[ "$minecraft_version" =~ ^[0-9A-Za-z._-]+$ ]] ||
        fail "Invalid Minecraft version: $minecraft_version"

    local project_json
    project_json="$(
        curl \
            --fail \
            --silent \
            --show-error \
            --location \
            --header "User-Agent: $USER_AGENT" \
            "$PAPER_API/projects/paper"
    )" || fail "Could not query Paper versions"

    if ! jq -e \
        --arg version "$minecraft_version" \
        '[.versions[][]] | index($version) != null' \
        >/dev/null <<<"$project_json"
    then
        fail "Paper does not support Minecraft $minecraft_version"
    fi

    local builds_json
    builds_json="$(
        curl \
            --fail \
            --silent \
            --show-error \
            --location \
            --header "User-Agent: $USER_AGENT" \
            "$PAPER_API/projects/paper/versions/$minecraft_version/builds"
    )" || fail "Could not query builds for Minecraft $minecraft_version"

    local build_json
    build_json="$(
        jq -c '
            [
                .[]
                | select(
                    .channel == "STABLE"
                    and .downloads["server:default"] != null
                )
            ]
            | max_by(.id) // empty
        ' <<<"$builds_json"
    )"

    [[ -n "$build_json" ]] ||
        fail "No stable Paper build exists for Minecraft $minecraft_version"

    local build_id name url expected_hash expected_size

    build_id="$(jq -r '.id' <<<"$build_json")"
    name="$(jq -r '.downloads["server:default"].name' <<<"$build_json")"
    url="$(jq -r '.downloads["server:default"].url' <<<"$build_json")"
    expected_hash="$(jq -r '.downloads["server:default"].checksums.sha256' <<<"$build_json")"
    expected_size="$(jq -r '.downloads["server:default"].size' <<<"$build_json")"

    local target_dir temporary
    target_dir="$(dirname "$target")"
    mkdir -p "$target_dir"

    temporary="$(mktemp "$target_dir/.paper-download.XXXXXX")"
    trap 'rm -f "$temporary"' EXIT INT TERM

    printf 'Downloading Paper %s build %s\n' \
        "$minecraft_version" "$build_id"

    curl \
        --fail \
        --silent \
        --show-error \
        --location \
        --retry 3 \
        --header "User-Agent: $USER_AGENT" \
        --output "$temporary" \
        "$url" ||
        fail "Paper download failed"

    local actual_size
    actual_size="$(wc -c <"$temporary" | tr -d '[:space:]')"

    [[ "$actual_size" == "$expected_size" ]] ||
        fail "Paper download has the wrong size"

    local actual_hash
    actual_hash="$(shasum -a 256 "$temporary" | awk '{print $1}')"

    [[ "$actual_hash" == "$expected_hash" ]] ||
        fail "Paper SHA-256 verification failed"

    mv "$temporary" "$target"
    trap - EXIT INT TERM

    printf 'Installed %s as %s\n' "$name" "$target"
    printf 'SHA-256: %s\n' "$expected_hash"
}
