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
        printf 'Usage: %s <name> [config-file]\n' "$0" >&2
        exit 2
    fi

    local name="$1"
    local config="${2:-jarlet.conf}"

    [[ "$name" =~ ^[0-9A-Za-z._-]+$ ]] ||
        fail "Server name must be a simple name (letters, digits, ._-)"

    config="$(resolve_path "$config")"

    [[ -f "$config" ]] || fail "$config does not exist"

    command -v java >/dev/null || fail "Java is not installed"

    local root server_dir version port online_mode

    root="$(servers_root)"
    server_dir="$root/$name"

    [[ ! -e "$server_dir" ]] ||
        fail "A server named '$name' already exists at $server_dir"

    version="$(config_value MINECRAFT_VERSION "$config")"
    port="$(config_value PORT "$config")"
    online_mode="$(config_value ONLINE_MODE "$config")"

    [[ "$version" =~ ^[0-9A-Za-z._-]+$ ]] ||
        fail "Invalid MINECRAFT_VERSION"

    [[ "$port" =~ ^[0-9]+$ ]] &&
        (( port >= 1 && port <= 65535 )) ||
        fail "Invalid PORT"

    [[ "$online_mode" == "true" || "$online_mode" == "false" ]] ||
        fail "ONLINE_MODE must be true or false"

    mkdir -p "$server_dir"

    if [[ ! -f "$server_dir/server.jar" ]]; then
        "$SCRIPT_DIR/install.sh" "$version" "$server_dir/server.jar"
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

    awk -F= -v name="$name" '
        $0 !~ /^[[:space:]]*#/ && $1 == "NAME" { print "NAME=" name; found=1; next }
        { print }
        END { if (!found) print "NAME=" name }
    ' "$config" >"$server_dir/jarlet.conf"

    printf 'Server "%s" is ready at %s\n' "$name" "$server_dir"
    printf 'Run: %s/start.sh %s\n' "$SCRIPT_DIR" "$name"
}

main "$@"
