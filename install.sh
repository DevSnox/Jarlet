#!/usr/bin/env bash

set -Eeuo pipefail
umask 077

readonly REPOSITORY="DevSnox/Jarlet"
readonly API_BASE="https://api.github.com/repos/${REPOSITORY}"
readonly API_VERSION="2026-03-10"
readonly CHANNEL="${JARLET_CHANNEL:-release}"
readonly INSTALL_DIR="${JARLET_INSTALL_DIR:-/usr/local/bin}"

die() {
  printf 'jarlet installer: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
}

for command_name in curl python3 sha256sum install mktemp awk; do
  require_command "$command_name"
done

[[ "$(uname -s)" == "Linux" ]] || die "only Linux is supported"

case "$(uname -m)" in
  x86_64 | amd64) readonly ARCHITECTURE="x86_64" ;;
  *) die "unsupported architecture: $(uname -m); supported: x86_64" ;;
esac

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

[[ "$INSTALL_DIR" == /* ]] || die "JARLET_INSTALL_DIR must be an absolute path"
[[ "$INSTALL_DIR" != *$'\n'* && "$INSTALL_DIR" != *$'\r'* ]] || die "invalid install directory"

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
  python3 - "$CHANNEL" "$ARCHITECTURE" "$REPOSITORY" "$RELEASE_JSON" <<'PYTHON'
import json
import re
import sys

channel, architecture, repository, metadata_path = sys.argv[1:]

with open(metadata_path, "r", encoding="utf-8") as metadata_file:
    response = json.load(metadata_file)

releases = response if isinstance(response, list) else [response]

if channel == "release":
    candidates = [
        release for release in releases
        if not release.get("draft", True) and not release.get("prerelease", True)
    ]
else:
    channel_pattern = re.compile(
        rf"(?:^|[.-]){re.escape(channel)}(?:[.-]|$)",
        re.IGNORECASE,
    )
    candidates = [
        release for release in releases
        if not release.get("draft", True)
        and release.get("prerelease") is True
        and channel_pattern.search(release.get("tag_name", ""))
    ]

if not candidates:
    raise SystemExit(f"No published Jarlet {channel} release was found")

release = candidates[0]
tag = release.get("tag_name", "")

if not re.fullmatch(r"v?[0-9][0-9A-Za-z.+-]*", tag):
    raise SystemExit(f"Release has an unsafe or unsupported tag: {tag!r}")

asset_name = f"jarlet-{tag}-linux-{architecture}"
checksum_name = f"{asset_name}.sha256"
assets = {asset.get("name"): asset for asset in release.get("assets", [])}

try:
    binary_asset = assets[asset_name]
    checksum_asset = assets[checksum_name]
except KeyError as error:
    raise SystemExit(f"Required release asset is missing: {error.args[0]}")

expected_url_prefix = f"https://github.com/{repository}/releases/download/"

def validated_asset(asset, expected_name):
    if asset.get("state") != "uploaded":
        raise SystemExit(f"Asset is not completely uploaded: {expected_name}")

    url = asset.get("browser_download_url", "")
    if not url.startswith(expected_url_prefix):
        raise SystemExit(f"Asset has an unexpected download URL: {expected_name}")

    digest = asset.get("digest", "")
    digest_match = re.fullmatch(r"sha256:([0-9a-fA-F]{64})", digest)
    if not digest_match:
        raise SystemExit(f"Asset has no valid GitHub SHA-256 digest: {expected_name}")

    return url, digest_match.group(1).lower()

binary_url, binary_digest = validated_asset(binary_asset, asset_name)
checksum_url, checksum_digest = validated_asset(checksum_asset, checksum_name)

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

[[ "${#release_metadata[@]}" -eq 7 ]] || die "release metadata validation failed"

readonly RELEASE_TAG="${release_metadata[0]}"
readonly ASSET_NAME="${release_metadata[1]}"
readonly BINARY_URL="${release_metadata[2]}"
readonly GITHUB_BINARY_DIGEST="${release_metadata[3]}"
readonly CHECKSUM_NAME="${release_metadata[4]}"
readonly CHECKSUM_URL="${release_metadata[5]}"
readonly GITHUB_CHECKSUM_DIGEST="${release_metadata[6]}"
readonly BINARY_PATH="${TEMPORARY_DIRECTORY}/${ASSET_NAME}"
readonly CHECKSUM_PATH="${TEMPORARY_DIRECTORY}/${CHECKSUM_NAME}"

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

printf 'Downloading Jarlet %s (%s channel)...\n' "$RELEASE_TAG" "$CHANNEL"
download_asset "$BINARY_URL" "$BINARY_PATH"
download_asset "$CHECKSUM_URL" "$CHECKSUM_PATH"

# Verification 1: downloaded binary against GitHub's release-asset digest.
readonly ACTUAL_BINARY_DIGEST="$(sha256sum "$BINARY_PATH" | awk '{print $1}')"
[[ "$ACTUAL_BINARY_DIGEST" == "$GITHUB_BINARY_DIGEST" ]] || \
  die "binary does not match GitHub's release-asset digest"

# Verify that the downloaded checksum file itself matches GitHub's digest.
readonly ACTUAL_CHECKSUM_DIGEST="$(sha256sum "$CHECKSUM_PATH" | awk '{print $1}')"
[[ "$ACTUAL_CHECKSUM_DIGEST" == "$GITHUB_CHECKSUM_DIGEST" ]] || \
  die "checksum file does not match GitHub's release-asset digest"

# Parse exactly one checksum record and require the exact expected filename.
readonly PUBLISHED_BINARY_DIGEST="$({
  python3 - "$CHECKSUM_PATH" "$ASSET_NAME" <<'PYTHON'
import re
import sys

checksum_path, expected_name = sys.argv[1:]

with open(checksum_path, "r", encoding="ascii") as checksum_file:
    contents = checksum_file.read()

match = re.fullmatch(r"([0-9a-fA-F]{64})  ([^\r\n]+)\n?", contents)
if not match:
    raise SystemExit("Checksum file has an invalid format")

digest, filename = match.groups()
if filename != expected_name:
    raise SystemExit("Checksum file names an unexpected asset")

print(digest.lower())
PYTHON
})"

# Verification 2: downloaded binary against the separately published checksum file.
[[ "$ACTUAL_BINARY_DIGEST" == "$PUBLISHED_BINARY_DIGEST" ]] || \
  die "binary does not match the published checksum file"

readonly DESTINATION="${INSTALL_DIR}/jarlet"
[[ ! -L "$DESTINATION" ]] || die "refusing to replace symlink: $DESTINATION"

if [[ "$(id -u)" -eq 0 ]]; then
  install -d -m 0755 -- "$INSTALL_DIR"
  install -m 0755 -- "$BINARY_PATH" "$DESTINATION"
elif [[ -d "$INSTALL_DIR" && -w "$INSTALL_DIR" ]]; then
  install -m 0755 -- "$BINARY_PATH" "$DESTINATION"
else
  require_command sudo
  sudo -- install -d -m 0755 -- "$INSTALL_DIR"
  sudo -- install -m 0755 -- "$BINARY_PATH" "$DESTINATION"
fi

printf 'Installed Jarlet %s at %s\n' "$RELEASE_TAG" "$DESTINATION"
