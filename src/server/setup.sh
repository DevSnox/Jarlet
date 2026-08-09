#!/usr/bin/env bash
set -euo pipefail

readonly SCRIPT_DIR="$(
    CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd
)"

# shellcheck source=../lib/lib.sh
. "$SCRIPT_DIR/../lib/lib.sh"

resolve_path() {
    local path="$1"
    local dir base

    dir="$(CDPATH= cd -- "$(dirname -- "$path")" 2>/dev/null && pwd)" ||
        fail "Directory does not exist: $(dirname -- "$path")"
    base="$(basename -- "$path")"

    printf '%s/%s' "$dir" "$base"
}

main() {
    if (( $# < 1 )); then
        printf 'Usage: %s <name> [template-file]\n' "$0" >&2
        exit 2
    fi

    command -v jq >/dev/null || fail "jq is required"
    require_toml_tools

    local name="$1"
    local template_name
    template_name="$(template_filename)"
    local config="${2:-$template_name}"

    [[ "$name" =~ ^[0-9A-Za-z._-]+$ ]] ||
        fail "Server name must be a simple name (letters, digits, ._-)"

    config="$(resolve_path "$config")"

    [[ -f "$config" ]] || fail "$config does not exist"

    command -v java >/dev/null || fail "Java is not installed"

    local root server_dir version port online_mode package config_json

    root="$(servers_root)"
    server_dir="$root/$name"

    [[ ! -e "$server_dir" ]] ||
        fail "A server named '$name' already exists at $server_dir"

    config_json="$(toml_to_json "$config")" ||
        fail "Could not parse $config as TOML"

    version="$(jq -r '.server.minecraft_version // empty' <<<"$config_json")"
    port="$(jq -r '.server.port // empty' <<<"$config_json")"
    online_mode="$(jq -r '.server.online_mode // empty' <<<"$config_json")"
    package="$(jq -r '.server.package // "paper"' <<<"$config_json")"

    [[ "$version" =~ ^[0-9A-Za-z._-]+$ ]] ||
        fail "Invalid [server].minecraft_version"

    [[ "$port" =~ ^[0-9]+$ ]] &&
        (( port >= 1 && port <= 65535 )) ||
        fail "Invalid [server].port"

    [[ "$online_mode" == "true" || "$online_mode" == "false" ]] ||
        fail "[server].online_mode must be true or false"

    [[ "$package" == "paper" ]] ||
        fail "Unknown [server].package '$package'; only 'paper' is implemented"

    mkdir -p "$server_dir"

    if [[ ! -f "$server_dir/server.jar" ]]; then
        "$SCRIPT_DIR/install.sh" "$version" "$server_dir/server.jar" "$package"
    fi

    if [[ ! -f "$server_dir/eula.txt" ]]; then
        printf 'eula=true\n' >"$server_dir/eula.txt"
    fi

    if [[ ! -f "$server_dir/server.properties" ]]; then
        {
            printf 'server-port=%s\n' "$port"
            printf 'online-mode=%s\n' "$online_mode"
            printf 'motd=A Jarlet Minecraft Server\n'
            printf 'enable-command-block=false\n'
        } >"$server_dir/server.properties"
    fi

    # The instance name lives only in the directory name / CLI arg, never
    # in the template itself (see server-templating.md), so the per-server
    # copy is a plain, unmodified copy of the source template.
    cp "$config" "$server_dir/$template_name"

    printf 'Server "%s" is ready at %s\n' "$name" "$server_dir"
    printf 'Run: %s/start.sh %s\n' "$SCRIPT_DIR" "$name"
}

main "$@"
