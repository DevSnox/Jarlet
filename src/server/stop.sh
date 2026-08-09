#!/usr/bin/env bash
set -euo pipefail

readonly SCRIPT_DIR="$(
    CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd
)"

readonly SYS_CONFIG_FILE="$SCRIPT_DIR/../jarlet-sys.conf"

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

main() {
    if (( $# < 1 )); then
        printf 'Usage: %s <name>\n' "$0" >&2
        exit 2
    fi

    local name="$1"

    [[ "$name" =~ ^[0-9A-Za-z._-]+$ ]] ||
        fail "Server name must be a simple name (letters, digits, ._-)"

    local root server_dir pid_file server_pid command

    root="$(servers_root)"
    server_dir="$root/$name"

    [[ -d "$server_dir" ]] ||
        fail "No server named '$name' found at $server_dir"

    pid_file="$server_dir/.jarlet/server.pid"

    [[ -f "$pid_file" ]] ||
        fail "Server is not running"

    server_pid="$(cat "$pid_file")"

    [[ "$server_pid" =~ ^[0-9]+$ ]] ||
        fail "Invalid server PID"

    if ! kill -0 "$server_pid" 2>/dev/null; then
        rm -f "$pid_file"
        fail "Server is not running; removed stale PID file"
    fi

    command="$(ps -p "$server_pid" -o command=)"

    case "$command" in
        *java*"-jar server.jar"*)
            ;;
        *)
            fail "PID $server_pid does not appear to be the Paper server"
            ;;
    esac

    printf 'Stopping server "%s"...\n' "$name"
    kill -TERM "$server_pid"

    local stop_timeout
    stop_timeout="$(sys_config_value STOP_TIMEOUT_SECONDS)"

    [[ "$stop_timeout" =~ ^[1-9][0-9]*$ ]] ||
        fail "STOP_TIMEOUT_SECONDS must be a positive integer"

    local attempt

    for (( attempt = 1; attempt <= stop_timeout; attempt++ )); do
        if ! kill -0 "$server_pid" 2>/dev/null; then
            rm -f "$pid_file"
            printf 'Server stopped\n'
            exit 0
        fi

        sleep 1
    done

    fail "Server did not stop within $stop_timeout seconds; inspect logs/latest.log"
}

main "$@"
