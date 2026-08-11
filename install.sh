#!/usr/bin/env bash

set -Eeuo pipefail
umask 077

readonly REPOSITORY="DevSnox/Jarlet"
readonly API_BASE="https://api.github.com/repos/${REPOSITORY}"
readonly API_VERSION="2026-03-10"

readonly CHANNEL="${JARLET_CHANNEL:-release}"
readonly TAG="${JARLET_TAG:-}"

if [[ -n "$TAG" && -n "${JARLET_CHANNEL:-}" ]]; then
  printf 'jarlet installer: JARLET_TAG is set; ignoring JARLET_CHANNEL\n' >&2
fi

readonly SERVICE_USER="jarlet"
readonly SERVICE_GROUP="jarlet"
readonly SERVICE_HOME="/home/jarlet"

readonly BINARY_DIRECTORY="/usr/local/libexec/jarlet"
readonly BINARY_PATH="${BINARY_DIRECTORY}/jarlet"
readonly LAUNCHER_PATH="/usr/local/bin/jarlet"

readonly CONFIG_DIRECTORY="/etc/jarlet"
readonly ENV_FILE="${CONFIG_DIRECTORY}/jarlet.env"

die() {
  printf 'jarlet installer: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 ||
    die "required command not found: $1"
}

for command_name in \
  curl \
  python3 \
  sha256sum \
  install \
  mktemp \
  awk \
  getent \
  useradd \
  stat
do
  require_command "$command_name"
done

if [[ "$(id -u)" -ne 0 ]]; then
  require_command sudo
fi

as_root() {
  if [[ "$(id -u)" -eq 0 ]]; then
    "$@"
  else
    sudo -- "$@"
  fi
}

[[ "$(uname -s)" == "Linux" ]] ||
  die "only Linux is supported"

case "$(uname -m)" in
  x86_64 | amd64)
    readonly ARCHITECTURE="x86_64"
    ;;
  *)
    die "unsupported architecture: $(uname -m); supported: x86_64"
    ;;
esac

if [[ -n "$TAG" ]]; then
  [[ "$TAG" =~ ^v?[0-9][0-9A-Za-z.+-]*$ ]] ||
    die "invalid JARLET_TAG '${TAG}'"

  readonly RELEASE_API_URL="${API_BASE}/releases/tags/${TAG}"
else
  case "$CHANNEL" in
    alpha | beta | rc)
      readonly RELEASE_API_URL="${API_BASE}/releases?per_page=100"
      ;;
    release)
      readonly RELEASE_API_URL="${API_BASE}/releases/latest"
      ;;
    *)
      die "invalid JARLET_CHANNEL '${CHANNEL}'; use alpha, beta, rc, or release"
      ;;
  esac
fi

readonly TEMPORARY_DIRECTORY="$(mktemp -d)"
trap 'rm -rf -- "$TEMPORARY_DIRECTORY"' EXIT HUP INT TERM

readonly RELEASE_JSON="${TEMPORARY_DIRECTORY}/release.json"

curl \
  --proto '=https' \
  --proto-redir '=https' \
  --fail \
  --silent \
  --show-error \
  --location \
  --retry 3 \
  --retry-all-errors \
  --connect-timeout 15 \
  --max-time 60 \
  --header 'Accept: application/vnd.github+json' \
  --header "X-GitHub-Api-Version: ${API_VERSION}" \
  --output "$RELEASE_JSON" \
  "$RELEASE_API_URL"

mapfile -t release_metadata < <(
  python3 - \
    "$CHANNEL" \
    "$ARCHITECTURE" \
    "$REPOSITORY" \
    "$RELEASE_JSON" \
    "$TAG" <<'PYTHON'
import json
import re
import sys

channel, architecture, repository, metadata_path, tag_arg = sys.argv[1:]

with open(metadata_path, "r", encoding="utf-8") as metadata_file:
    response = json.load(metadata_file)

releases = response if isinstance(response, list) else [response]

if tag_arg:
    candidates = [
        release
        for release in releases
        if not release.get("draft", True)
    ]

    if not candidates:
        raise SystemExit(
            f"No published Jarlet release found for tag {tag_arg!r}"
        )
elif channel == "release":
    candidates = [
        release
        for release in releases
        if not release.get("draft", True)
        and not release.get("prerelease", True)
    ]

    if not candidates:
        raise SystemExit(
            f"No published Jarlet {channel} release was found"
        )
else:
    channel_pattern = re.compile(
        rf"(?:^|[.-]){re.escape(channel)}(?:[.-]|$)",
        re.IGNORECASE,
    )

    candidates = [
        release
        for release in releases
        if not release.get("draft", True)
        and release.get("prerelease") is True
        and channel_pattern.search(release.get("tag_name", ""))
    ]

    if not candidates:
        raise SystemExit(
            f"No published Jarlet {channel} release was found"
        )

release = candidates[0]
tag = release.get("tag_name", "")

if not re.fullmatch(r"v?[0-9][0-9A-Za-z.+-]*", tag):
    raise SystemExit(
        f"Release has an unsafe or unsupported tag: {tag!r}"
    )

asset_name = f"jarlet-{tag}-linux-{architecture}"
checksum_name = f"{asset_name}.sha256"

assets = {
    asset.get("name"): asset
    for asset in release.get("assets", [])
}

try:
    binary_asset = assets[asset_name]
    checksum_asset = assets[checksum_name]
except KeyError as error:
    raise SystemExit(
        f"Required release asset is missing: {error.args[0]}"
    )

expected_url_prefix = (
    f"https://github.com/{repository}/releases/download/"
)

def validate_asset(asset, expected_name):
    if asset.get("state") != "uploaded":
        raise SystemExit(
            f"Asset is not completely uploaded: {expected_name}"
        )

    url = asset.get("browser_download_url", "")

    if not url.startswith(expected_url_prefix):
        raise SystemExit(
            f"Asset has an unexpected download URL: {expected_name}"
        )

    digest = asset.get("digest", "")
    digest_match = re.fullmatch(
        r"sha256:([0-9a-fA-F]{64})",
        digest,
    )

    if not digest_match:
        raise SystemExit(
            f"Asset has no valid GitHub SHA-256 digest: "
            f"{expected_name}"
        )

    return url, digest_match.group(1).lower()

binary_url, binary_digest = validate_asset(
    binary_asset,
    asset_name,
)

checksum_url, checksum_digest = validate_asset(
    checksum_asset,
    checksum_name,
)

for value in (
    tag,
    asset_name,
    binary_url,
    binary_digest,
    checksum_name,
    checksum_url,
    checksum_digest,
):
    print(value)
PYTHON
)

[[ "${#release_metadata[@]}" -eq 7 ]] ||
  die "release metadata validation failed"

readonly RELEASE_TAG="${release_metadata[0]}"
readonly ASSET_NAME="${release_metadata[1]}"
readonly BINARY_URL="${release_metadata[2]}"
readonly GITHUB_BINARY_DIGEST="${release_metadata[3]}"
readonly CHECKSUM_NAME="${release_metadata[4]}"
readonly CHECKSUM_URL="${release_metadata[5]}"
readonly GITHUB_CHECKSUM_DIGEST="${release_metadata[6]}"

readonly DOWNLOADED_BINARY="${TEMPORARY_DIRECTORY}/${ASSET_NAME}"
readonly DOWNLOADED_CHECKSUM="${TEMPORARY_DIRECTORY}/${CHECKSUM_NAME}"

download_asset() {
  local url="$1"
  local output="$2"

  curl \
    --proto '=https' \
    --proto-redir '=https' \
    --fail \
    --silent \
    --show-error \
    --location \
    --retry 3 \
    --retry-all-errors \
    --connect-timeout 15 \
    --max-time 900 \
    --output "$output" \
    "$url"
}

if [[ -n "$TAG" ]]; then
  printf 'Downloading Jarlet %s (pinned tag)...\n' "$RELEASE_TAG"
else
  printf 'Downloading Jarlet %s (%s channel)...\n' \
    "$RELEASE_TAG" \
    "$CHANNEL"
fi

download_asset "$BINARY_URL" "$DOWNLOADED_BINARY"
download_asset "$CHECKSUM_URL" "$DOWNLOADED_CHECKSUM"

# Verification 1: binary against GitHub's asset digest.
readonly ACTUAL_BINARY_DIGEST="$(
  sha256sum "$DOWNLOADED_BINARY" |
    awk '{print $1}'
)"

[[ "$ACTUAL_BINARY_DIGEST" == "$GITHUB_BINARY_DIGEST" ]] ||
  die "binary does not match GitHub's release-asset digest"

# Verify that the checksum file itself matches GitHub's digest.
readonly ACTUAL_CHECKSUM_DIGEST="$(
  sha256sum "$DOWNLOADED_CHECKSUM" |
    awk '{print $1}'
)"

[[ "$ACTUAL_CHECKSUM_DIGEST" == "$GITHUB_CHECKSUM_DIGEST" ]] ||
  die "checksum file does not match GitHub's release-asset digest"

# Parse exactly one checksum entry and require the exact asset name.
readonly PUBLISHED_BINARY_DIGEST="$(
  python3 - \
    "$DOWNLOADED_CHECKSUM" \
    "$ASSET_NAME" <<'PYTHON'
import re
import sys

checksum_path, expected_name = sys.argv[1:]

with open(checksum_path, "r", encoding="ascii") as checksum_file:
    contents = checksum_file.read()

match = re.fullmatch(
    r"([0-9a-fA-F]{64})  ([^\r\n]+)\n?",
    contents,
)

if not match:
    raise SystemExit("Checksum file has an invalid format")

digest, filename = match.groups()

if filename != expected_name:
    raise SystemExit(
        "Checksum file names an unexpected asset"
    )

print(digest.lower())
PYTHON
)"

# Verification 2: binary against the published checksum file.
[[ "$ACTUAL_BINARY_DIGEST" == "$PUBLISHED_BINARY_DIGEST" ]] ||
  die "binary does not match the published checksum file"

printf 'Verified SHA-256: %s\n' "$ACTUAL_BINARY_DIGEST"

# Create the service account only when it does not already exist.
if ! getent passwd "$SERVICE_USER" >/dev/null; then
  as_root useradd \
    --system \
    --user-group \
    --create-home \
    --home-dir "$SERVICE_HOME" \
    --shell /usr/sbin/nologin \
    "$SERVICE_USER"
fi

passwd_entry="$(getent passwd "$SERVICE_USER")"

IFS=: read -r \
  actual_user \
  _ \
  actual_uid \
  actual_gid \
  _ \
  actual_home \
  actual_shell \
  <<< "$passwd_entry"

[[ "$actual_user" == "$SERVICE_USER" ]] ||
  die "service account validation failed"

[[ "$actual_uid" != "0" ]] ||
  die "refusing to use UID 0 for Jarlet"

[[ "$actual_home" == "$SERVICE_HOME" ]] ||
  die "existing jarlet user has unexpected home: $actual_home"

group_entry="$(getent group "$SERVICE_GROUP")" ||
  die "required group does not exist: $SERVICE_GROUP"

IFS=: read -r \
  actual_group \
  _ \
  actual_group_id \
  _ \
  <<< "$group_entry"

[[ "$actual_group" == "$SERVICE_GROUP" ]] ||
  die "service group validation failed"

[[ "$actual_gid" == "$actual_group_id" ]] ||
  die "jarlet user's primary group is not jarlet"

# Create and secure the isolated home.
as_root install -d \
  -o "$SERVICE_USER" \
  -g "$SERVICE_GROUP" \
  -m 0700 \
  "$SERVICE_HOME"

as_root install -d \
  -o "$SERVICE_USER" \
  -g "$SERVICE_GROUP" \
  -m 0700 \
  "${SERVICE_HOME}/.config"

as_root install -d \
  -o "$SERVICE_USER" \
  -g "$SERVICE_GROUP" \
  -m 0700 \
  "${SERVICE_HOME}/.local/share"

# Install the native executable behind the launcher.
as_root install -d \
  -o root \
  -g root \
  -m 0755 \
  "$BINARY_DIRECTORY"

as_root install \
  -o root \
  -g root \
  -m 0755 \
  "$DOWNLOADED_BINARY" \
  "$BINARY_PATH"

# Create the protected configuration directory.
as_root install -d \
  -o root \
  -g "$SERVICE_GROUP" \
  -m 0750 \
  "$CONFIG_DIRECTORY"

# Preserve an existing environment file during upgrades.
if as_root test -L "$ENV_FILE"; then
  die "refusing to use symlink as environment file: $ENV_FILE"
fi

if ! as_root test -e "$ENV_FILE"; then
  readonly TEMPORARY_ENV_TEMPLATE="${TEMPORARY_DIRECTORY}/jarlet.env"

  cat > "$TEMPORARY_ENV_TEMPLATE" <<'ENV_TEMPLATE'
# Jarlet environment file.
#
# Every setting below is optional and commented out. Uncomment a line and
# fill in a value to enable it; leave it as-is to use Jarlet's default.

# Raises GitHub's unauthenticated API rate limit (60 req/hr) to 5000 req/hr
# when installing/updating GitHub-releases-sourced plugins.
#JARLET_GITHUB_TOKEN=

# Required to install or update Hangar-sourced plugins; Hangar requires
# authentication for essentially every endpoint. Exchanged for a short-lived
# JWT on demand.
#JARLET_HANGAR_API_KEY=

# Overrides where Jarlet's own files (currently: the trusted external
# sources list) live. Defaults to ~/jarlet.
#JARLET_HOME=

# Overrides where server instances live. Must be an absolute path if set.
# Defaults to ~/jarlet/servers.
#JARLET_SERVERS_DIR=
ENV_TEMPLATE

  as_root install \
    -o root \
    -g "$SERVICE_GROUP" \
    -m 0640 \
    "$TEMPORARY_ENV_TEMPLATE" \
    "$ENV_FILE"
fi

as_root chown root:"$SERVICE_GROUP" "$ENV_FILE"
as_root chmod 0640 "$ENV_FILE"

readonly TEMPORARY_LAUNCHER="${TEMPORARY_DIRECTORY}/jarlet-launcher"

cat > "$TEMPORARY_LAUNCHER" <<'LAUNCHER'
#!/usr/bin/env bash

set -Eeuo pipefail

readonly SERVICE_USER="jarlet"
readonly SERVICE_HOME="/home/jarlet"
readonly BINARY="/usr/local/libexec/jarlet/jarlet"
readonly ENV_FILE="/etc/jarlet/jarlet.env"
readonly CALLER_TERM="${TERM:-dumb}"

[[ -x "$BINARY" ]] || {
  echo "Jarlet binary is missing or not executable: $BINARY" >&2
  exit 1
}

[[ ! -L "$ENV_FILE" ]] || {
  echo "Jarlet environment file must not be a symlink" >&2
  exit 1
}

target_uid="$(id -u "$SERVICE_USER")"

target_command=(
  env -i
  HOME="$SERVICE_HOME"
  USER="$SERVICE_USER"
  LOGNAME="$SERVICE_USER"
  PATH="/usr/local/bin:/usr/bin:/bin"
  LANG="C.UTF-8"
  TERM="$CALLER_TERM"
  XDG_CONFIG_HOME="$SERVICE_HOME/.config"
  XDG_DATA_HOME="$SERVICE_HOME/.local/share"
  bash
  --noprofile
  --norc
  -c
  '
    set -Eeuo pipefail
    umask 077

    [[ -r /etc/jarlet/jarlet.env ]] || {
      echo "Jarlet environment file is not readable" >&2
      exit 1
    }

    set -a
    source /etc/jarlet/jarlet.env
    set +a

    mkdir -p "$XDG_CONFIG_HOME" "$XDG_DATA_HOME"
    cd "$HOME"

    exec /usr/local/libexec/jarlet/jarlet "$@"
  '
  jarlet
  "$@"
)

if [[ "$(id -u)" -eq "$target_uid" ]]; then
  exec "${target_command[@]}"
fi

command -v sudo >/dev/null 2>&1 || {
  echo "sudo is required to run Jarlet as the isolated user" >&2
  exit 1
}

exec sudo \
  -u "$SERVICE_USER" \
  -H \
  "${target_command[@]}"
LAUNCHER

chmod 0755 "$TEMPORARY_LAUNCHER"

as_root install \
  -o root \
  -g root \
  -m 0755 \
  "$TEMPORARY_LAUNCHER" \
  "$LAUNCHER_PATH"

printf '\nInstalled Jarlet %s\n' "$RELEASE_TAG"
printf 'Binary:      %s\n' "$BINARY_PATH"
printf 'Launcher:    %s\n' "$LAUNCHER_PATH"
printf 'Environment: %s\n' "$ENV_FILE"
printf 'Home:        %s\n' "$SERVICE_HOME"
printf '\nConfigure secrets with:\n'
printf '  sudoedit %s\n' "$ENV_FILE"
printf '\nRun with:\n'
printf '  jarlet\n'
