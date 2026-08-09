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

# Routes to a server-software source adapter under ../adapter/server/,
# lazily and only once per invocation -- mirrors plugin/router.sh's
# route_plugin() shape (self-describing adapters, dynamic dispatch, lazy
# load-on-first-use, zero package-specific string literals here), so a
# second server-software package later is a small addition, not a rewrite.
# See ../adapter/server/paper.sh's header for the full adapter contract.
#
# Bash 3.2 (macOS's default /usr/bin/bash, since Apple stopped bundling
# GPLv3 bash) has no associative arrays, so loaded adapters are tracked
# via dynamically-named plain variables (indirect expansion, bash 2.x+,
# and printf -v, bash 3.1+) instead of `declare -A`. Do not reintroduce
# `declare -A` here -- it breaks on any user still on the system bash.
adapter_loaded_var() {
    printf 'ADAPTER_LOADED_%s' "${1//-/_}"
}

dispatch_install() {
    local package="$1" minecraft_version="$2" target="$3"

    local adapter_file="$SCRIPT_DIR/../adapter/server/$package.sh"

    [[ -f "$adapter_file" ]] ||
        fail "Unknown [server].package '$package'; no adapter is implemented for this package"

    local loaded_var
    loaded_var="$(adapter_loaded_var "$package")"

    if [[ -z "${!loaded_var:-}" ]]; then
        local ADAPTER_SOURCE_NAME="" ADAPTER_ENTRY_FUNCTION=""
        # shellcheck source=/dev/null
        . "$adapter_file"

        [[ "$ADAPTER_SOURCE_NAME" == "$package" ]] ||
            fail "Adapter '$adapter_file' declares ADAPTER_SOURCE_NAME='$ADAPTER_SOURCE_NAME', expected '$package'"

        [[ -n "$ADAPTER_ENTRY_FUNCTION" ]] ||
            fail "Adapter '$adapter_file' did not set ADAPTER_ENTRY_FUNCTION"

        declare -F "$ADAPTER_ENTRY_FUNCTION" >/dev/null ||
            fail "Adapter '$adapter_file' declares ADAPTER_ENTRY_FUNCTION='$ADAPTER_ENTRY_FUNCTION' but that function is not defined"

        printf -v "$loaded_var" '%s' "$ADAPTER_ENTRY_FUNCTION"
    fi

    "${!loaded_var}" "$minecraft_version" "$target"
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
