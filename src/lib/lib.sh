# lib.sh — shared helpers sourced by setup.sh, start.sh, and plugins.sh.
#
# Sourced (not exec'd) after the caller has already set its own readonly
# SCRIPT_DIR (all three do this as their first executable step), since
# jarlet-sys.conf lives alongside these scripts and SYS_CONFIG_FILE below
# is derived from that.
#
# stop.sh deliberately does NOT source this file — it keeps its own inline
# copies of fail()/config_value()/sys_config_value()/servers_root(), left
# untouched by the jarlet.conf -> jarlet.toml migration since stop.sh has
# no config-file dependency of its own.

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

# The standard filename for a Jarlet server template/instance file, both
# the top-level example template and the per-server copy setup.sh writes.
# Kept out of this file as a literal per the "value-containing variables
# live in jarlet-sys.conf" project rule; this just reads it from there.
template_filename() {
    sys_config_value TEMPLATE_FILENAME
}

# Converts a jarlet.toml file to JSON so callers can use jq throughout,
# the same way install.sh does for the Paper API's JSON.
toml_to_json() {
    dasel -i toml -o json --compact --root < "$1"
}

# Converts a JSON document (read from stdin) back to TOML text on stdout.
# Used by plugins.sh's add/remove subcommands to persist a mutated
# jarlet.toml.
#
# dasel v3.11.2 has no "put"/"delete" mutation command (only `query` exists
# -- confirmed via `dasel --help`), and its query language has no array
# append/delete function either (both are v1/v2-only and error with
# "unknown function" on this version). So add/remove build the new document
# by decoding the whole file to JSON via toml_to_json(), mutating the
# resulting structure with jq (already a hard dependency), and re-encoding
# the *entire* document back to TOML with this function.
#
# IMPORTANT: this is a full, lossy rewrite. dasel's TOML writer does not
# round-trip comments or preserve key order/quoting style -- confirmed by
# testing against a scratch copy of jarlet.toml, where the header comment
# block was dropped and keys were alphabetized on write. This is an
# accepted, known tradeoff for add/remove; update never calls this function
# and so never touches jarlet.toml at all.
json_to_toml() {
    dasel -i json -o toml --root
}

# Fails with a clear message unless both TOML-parsing tools are available.
require_toml_tools() {
    command -v dasel >/dev/null ||
        fail "dasel is required to read $(template_filename) (e.g. 'brew install dasel')"
    command -v jq >/dev/null || fail "jq is required"
}
