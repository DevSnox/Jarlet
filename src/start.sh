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
    local foreground=false
    local accept_eula=false
    local argument

    for argument in "$@"; do
        case "$argument" in
            --foreground)
                foreground=true
                ;;
            --accept-eula)
                accept_eula=true
                ;;
            *)
                fail "Unknown argument: $argument"
                ;;
        esac
    done

    local config="$SCRIPT_DIR/jarlet.conf"

    [[ -f "$config" ]] ||
        fail "$config does not exist"

    if ! java -version >/dev/null 2>&1; then
        fail "Java is not installed or not registered"
    fi

    local dir memory version server_dir

    dir="$(config_value DIR "$config")"
    memory="$(config_value MEMORY "$config")"
    version="$(config_value MINECRAFT_VERSION "$config")"

    [[ "$dir" =~ ^[0-9A-Za-z._-]+$ ]] ||
        fail "DIR must be a simple relative directory name"

    [[ "$memory" =~ ^[1-9][0-9]*[MG]$ ]] ||
        fail "MEMORY must look like 2G or 2048M"

    [[ "$version" =~ ^[0-9A-Za-z._-]+$ ]] ||
        fail "Invalid MINECRAFT_VERSION"

    server_dir="$SCRIPT_DIR/$dir"

    if ! grep -q '^eula=true$' "$server_dir/eula.txt" 2>/dev/null; then
        [[ "$accept_eula" == true ]] ||
            fail "Run ./start.sh --accept-eula after reading https://aka.ms/MinecraftEULA"

        mkdir -p "$server_dir"
        printf 'eula=true\n' >"$server_dir/eula.txt"
    fi

    if [[ ! -f "$server_dir/server.jar" ]]; then
        [[ -x "$SCRIPT_DIR/install.sh" ]] ||
            fail "$SCRIPT_DIR/install.sh is missing or not executable"

        "$SCRIPT_DIR/install.sh" \
            "$version" \
            "$server_dir/server.jar"
    fi

    cd "$server_dir"

    [[ -f server.jar ]] ||
        fail "server.jar installation failed"

    mkdir -p .jarlet

    local pid_file=".jarlet/server.pid"

    if [[ -f "$pid_file" ]]; then
        local existing_pid
        existing_pid="$(cat "$pid_file")"

        if [[ "$existing_pid" =~ ^[0-9]+$ ]] &&
            kill -0 "$existing_pid" 2>/dev/null
        then
            fail "Server is already running with PID $existing_pid"
        fi

        rm -f "$pid_file"
    fi

    printf 'Starting Paper %s with %s memory\n' \
        "$version" \
        "$memory"

    if [[ "$foreground" == true ]]; then
        exec java \
            "-Xms$memory" \
            "-Xmx$memory" \
            -Dfile.encoding=UTF-8 \
            -jar server.jar \
            nogui
    fi

    nohup java \
        "-Xms$memory" \
        "-Xmx$memory" \
        -Dfile.encoding=UTF-8 \
        -jar server.jar \
        nogui \
        </dev/null \
        >/dev/null \
        2>&1 &

    local server_pid="$!"
    printf '%s\n' "$server_pid" >"$pid_file"

    sleep 2

    if ! kill -0 "$server_pid" 2>/dev/null; then
        rm -f "$pid_file"

        if [[ -f logs/latest.log ]]; then
            tail -n 30 logs/latest.log >&2
        fi

        fail "Paper stopped during startup"
    fi

    printf 'Server started with PID %s\n' "$server_pid"
    printf 'Logs: %s/logs/latest.log\n' "$server_dir"
}

main "$@"
