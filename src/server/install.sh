#!/usr/bin/env bash
set -euo pipefail

readonly SCRIPT_DIR="$(
    CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd
)"

# shellcheck source=../lib/lib.sh
. "$SCRIPT_DIR/../lib/lib.sh"

readonly PROJECT_NAME="$(sys_config_value PROJECT_NAME)"
readonly REPO_URL="$(sys_config_value REPO_URL)"

readonly VERSION_SCRIPT="$SCRIPT_DIR/../lib/version.sh"
[[ -x "$VERSION_SCRIPT" ]] ||
    fail "$VERSION_SCRIPT does not exist or is not executable"

JARLET_VERSION="$("$VERSION_SCRIPT")" ||
    fail "Could not determine jarlet version"
readonly JARLET_VERSION

readonly USER_AGENT="${PROJECT_NAME}/${JARLET_VERSION} (${REPO_URL})"

# Routes to a server-software source adapter under ../source/server/,
# lazily and only once per invocation -- mirrors plugin.sh's
# dispatch_plugin() shape (explicit case, explicit per-package entry-point
# function, lazy load-on-first-use), so a second server-software package
# later is a small addition, not a rewrite.
dispatch_install() {
    local package="$1" minecraft_version="$2" target="$3"

    case "$package" in
        paper)
            command -v curl >/dev/null || fail "curl is required for the paper package"
            command -v jq >/dev/null || fail "jq is required for the paper package"
            command -v shasum >/dev/null || fail "shasum is required for the paper package"
            # shellcheck source=../source/server/paper.sh
            . "$SCRIPT_DIR/../source/server/paper.sh"
            install_paper_server "$minecraft_version" "$target"
            ;;
        *)
            fail "Unknown [server].package '$package'; only 'paper' is implemented"
            ;;
    esac
}

main() {
    if (( $# < 1 )); then
        printf 'Usage: %s <minecraft-version> [target] [package]\n' "$0" >&2
        exit 2
    fi

    local minecraft_version="$1"
    local target="${2:-server.jar}"
    local package="${3:-paper}"

    dispatch_install "$package" "$minecraft_version" "$target"
}

main "$@"
