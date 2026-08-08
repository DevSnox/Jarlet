#!/usr/bin/env bash
set -euo pipefail

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
    local config="jarlet.conf"

    [[ -f "$config" ]] || fail "$config does not exist"

    command -v java >/dev/null || fail "Java is not installed"

    local dir version port online_mode

    dir="$(config_value DIR "$config")"
    version="$(config_value MINECRAFT_VERSION "$config")"
    port="$(config_value PORT "$config")"
    online_mode="$(config_value ONLINE_MODE "$config")"

    [[ "$dir" =~ ^[0-9A-Za-z._-]+$ ]] ||
        fail "DIR must be a simple relative directory name"

    mkdir -p "$dir"

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

    if [[ ! -f "$dir/server.jar" ]]; then
        ./install.sh "$version" "$dir/server.jar"
    fi

    if [[ ! -f "$dir/eula.txt" ]]; then
        printf 'eula=true\n' >"$dir/eula.txt"
    fi

    if [[ ! -f "$dir/server.properties" ]]; then
        {
            printf 'server-port=%s\n' "$port"
            printf 'online-mode=%s\n' "$online_mode"
            printf 'motd=A Jarlet Minecraft Server\n'
            printf 'enable-command-block=false\n'
        } >"$dir/server.properties"
    fi

    printf 'Server is ready. Run ./start.sh\n'
}

main "$@"
