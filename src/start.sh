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

main() {
    local foreground=false
    local accept_eula=false
    local name=""
    local config_arg=""
    local argument

    for argument in "$@"; do
        case "$argument" in
            --foreground)
                foreground=true
                ;;
            --accept-eula)
                accept_eula=true
                ;;
            -*)
                fail "Unknown argument: $argument"
                ;;
            *)
                if [[ -z "$name" ]]; then
                    name="$argument"
                elif [[ -z "$config_arg" ]]; then
                    config_arg="$argument"
                else
                    fail "Unexpected argument: $argument"
                fi
                ;;
        esac
    done

    [[ -n "$name" ]] ||
        fail "Usage: $0 <name> [config-file] [--foreground] [--accept-eula]"

    [[ "$name" =~ ^[0-9A-Za-z._-]+$ ]] ||
        fail "Server name must be a simple name (letters, digits, ._-)"

    local root server_dir config

    root="$(servers_root)"
    server_dir="$root/$name"
    config="$server_dir/jarlet.conf"

    if [[ ! -f "$config" ]]; then
        [[ -x "$SCRIPT_DIR/setup.sh" ]] ||
            fail "$SCRIPT_DIR/setup.sh is missing or not executable"

        "$SCRIPT_DIR/setup.sh" "$name" "${config_arg:-jarlet.conf}"
    fi

    if ! java -version >/dev/null 2>&1; then
        fail "Java is not installed or not registered"
    fi

    local memory version

    memory="$(config_value MEMORY "$config")"
    version="$(config_value MINECRAFT_VERSION "$config")"

    [[ "$memory" =~ ^[1-9][0-9]*[MG]$ ]] ||
        fail "MEMORY must look like 2G or 2048M"

    [[ "$version" =~ ^[0-9A-Za-z._-]+$ ]] ||
        fail "Invalid MINECRAFT_VERSION"

    if ! grep -q '^eula=true$' "$server_dir/eula.txt" 2>/dev/null; then
        [[ "$accept_eula" == true ]] ||
            fail "Run $0 $name --accept-eula after reading https://aka.ms/MinecraftEULA"

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

    local startup_check_delay
    startup_check_delay="$(sys_config_value STARTUP_CHECK_DELAY_SECONDS)"

    [[ "$startup_check_delay" =~ ^[0-9]+$ ]] ||
        fail "STARTUP_CHECK_DELAY_SECONDS must be a non-negative integer"

    sleep "$startup_check_delay"

    if ! kill -0 "$server_pid" 2>/dev/null; then
        rm -f "$pid_file"

        if [[ -f logs/latest.log ]]; then
            tail -n 30 logs/latest.log >&2
        fi

        fail "Paper stopped during startup"
    fi

    printf 'Server "%s" started with PID %s\n' "$name" "$server_pid"
    printf 'Logs: %s/logs/latest.log\n' "$server_dir"
}

main "$@"
