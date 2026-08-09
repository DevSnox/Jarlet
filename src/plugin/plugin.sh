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

# store.sh: local persistence (plugins-state.json + jarlet.toml rewrites).
# resolve.sh: resolves a bare CLI identifier to a concrete (source, id).
# router.sh: picks/loads the source adapter for a declared entry.
# redirect.sh: resolves a source's external-hosting gate to another adapter.
# commands.sh: the add/remove subcommand implementations.
# list.sh: the list subcommand implementation.
# See each file's header comment for its exact contract.
# shellcheck source=store.sh
. "$SCRIPT_DIR/store.sh"
# shellcheck source=resolve.sh
. "$SCRIPT_DIR/resolve.sh"
# shellcheck source=router.sh
. "$SCRIPT_DIR/router.sh"
# shellcheck source=redirect.sh
. "$SCRIPT_DIR/redirect.sh"
# shellcheck source=commands.sh
. "$SCRIPT_DIR/commands.sh"
# shellcheck source=list.sh
. "$SCRIPT_DIR/list.sh"

usage() {
    printf 'Usage: %s <name> [add <identifier> [--pin <version> | --channel <name>] [--source <hangar|spiget|github-releases>] | remove <identifier> | update [<identifier>] | list [--page <n> | --all]]\n' "$0" >&2
}

main() {
    if (( $# < 1 )); then
        usage
        exit 2
    fi

    local name="$1"
    shift

    [[ "$name" =~ ^[0-9A-Za-z._-]+$ ]] ||
        fail "Server name must be a simple name (letters, digits, ._-)"

    command -v jq >/dev/null || fail "jq is required"
    require_toml_tools

    local template_name
    template_name="$(template_filename)"

    local root server_dir toml_file
    root="$(servers_root)"
    server_dir="$root/$name"

    [[ -d "$server_dir" ]] ||
        fail "No server named '$name' found at $server_dir"

    toml_file="$server_dir/$template_name"

    [[ -f "$toml_file" ]] ||
        fail "$toml_file does not exist. Run setup.sh (or start.sh) for '$name' first to generate it."

    local plugins_json
    plugins_json="$(toml_to_json "$toml_file")" ||
        fail "Could not parse $toml_file as TOML"

    local plugins_dir
    plugins_dir="$server_dir/plugins"
    mkdir -p "$plugins_dir"

    local subcommand="${1:-}"
    case "$subcommand" in
        add)
            shift
            cmd_add "$server_dir" "$plugins_dir" "$toml_file" "$plugins_json" "$@"
            ;;
        remove)
            shift
            cmd_remove "$server_dir" "$plugins_dir" "$toml_file" "$plugins_json" "$@"
            ;;
        update)
            shift
            if (( $# == 0 )); then
                run_update_all "$server_dir" "$plugins_dir" "$plugins_json"
            elif (( $# == 1 )); then
                run_update_one "$plugins_json" "$server_dir" "$plugins_dir" "$1"
            else
                fail "Usage: $0 <name> update [<identifier>]"
            fi
            ;;
        list)
            shift
            cmd_list "$server_dir" "$plugins_json" "$@"
            ;;
        "")
            # Backward-compatible default: bare `plugins.sh <name>` behaves
            # exactly like `plugins.sh <name> update` with no target --
            # process every declared plugin.
            run_update_all "$server_dir" "$plugins_dir" "$plugins_json"
            ;;
        *)
            usage
            fail "Unknown subcommand '$subcommand' (expected add, remove, update, or list)"
            ;;
    esac
}

main "$@"
