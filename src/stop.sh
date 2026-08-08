#!/usr/bin/env bash
set -euo pipefail

readonly SCRIPT_DIR="$(
    CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd
)"

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

main() {
    local config="$SCRIPT_DIR/jarlet.conf"

    [[ -f "$config" ]] ||
        fail "$config does not exist"

    local dir server_dir pid_file server_pid command

    dir="$(config_value DIR "$config")"

    [[ "$dir" =~ ^[0-9A-Za-z._-]+$ ]] ||
        fail "DIR must be a simple relative directory name"

    server_dir="$SCRIPT_DIR/$dir"
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

    printf 'Stopping server...\n'
    kill -TERM "$server_pid"

    local attempt

    for attempt in {1..60}; do
        if ! kill -0 "$server_pid" 2>/dev/null; then
            rm -f "$pid_file"
            printf 'Server stopped\n'
            exit 0
        fi

        sleep 1
    done

    fail "Server did not stop within 60 seconds; inspect logs/latest.log"
}

main "$@"
