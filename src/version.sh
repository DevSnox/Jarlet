#!/usr/bin/env bash
set -euo pipefail

readonly SCRIPT_DIR="$(
    CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd
)"

readonly VERSION_FILE="$SCRIPT_DIR/VERSION"

fail() {
    printf 'Error: %s\n' "$1" >&2
    exit 1
}

version_value() {
    local key="$1"

    awk -F= -v key="$key" '
        $0 !~ /^[[:space:]]*#/ && $1 == key {
            sub(/^[^=]*=/, "")
            print
            exit
        }
    ' "$VERSION_FILE"
}

main() {
    [[ -f "$VERSION_FILE" ]] ||
        fail "$VERSION_FILE does not exist"

    local include_prefix=false

    case "${1:-}" in
        "")
            ;;
        -T)
            include_prefix=true
            ;;
        *)
            fail "Usage: ./version.sh [-T]"
            ;;
    esac

    (( $# <= 1 )) ||
        fail "Usage: ./version.sh [-T]"

    local prefix major minor patch stage roll version

    prefix="$(version_value PREFIX)"
    major="$(version_value MAJOR)"
    minor="$(version_value MINOR)"
    patch="$(version_value PATCH)"
    stage="$(version_value STAGE)"
    roll="$(version_value ROLL)"

    [[ "$major" =~ ^(0|[1-9][0-9]*)$ ]] ||
        fail "Invalid MAJOR version"

    [[ "$minor" =~ ^(0|[1-9][0-9]*)$ ]] ||
        fail "Invalid MINOR version"

    [[ "$patch" =~ ^(0|[1-9][0-9]*)$ ]] ||
        fail "Invalid PATCH version"

    stage="$(
        printf '%s' "$stage" |
            tr '[:upper:]' '[:lower:]'
    )"

    version="${major}.${minor}.${patch}"

    case "$stage" in
        alpha|beta|rc)
            [[ "$roll" =~ ^[1-9][0-9]*$ ]] ||
                fail "ROLL must be a positive number"

            version="${version}-${stage}.${roll}"
            ;;
        stable)
            ;;
        *)
            fail "STAGE must be ALPHA, BETA, RC, or STABLE"
            ;;
    esac

    if [[ "$include_prefix" == true ]]; then
        printf '%s%s\n' "$prefix" "$version"
    else
        printf '%s\n' "$version"
    fi
}

main "$@"
