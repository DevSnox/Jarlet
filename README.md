> Warning: Jarlet is under active development and pre BETA. Features, commands, and configuration formats may change or break. Do not use it for important production servers without backups.

# Jarlet

Jarlet is a lightweight package manager for Minecraft servers. Describe a server once in a `jarlet.toml` template — software, version, plugins, update policy — and Jarlet builds it, keeps it in sync, and runs it.

No more manually re-downloading jars after every plugin update, or hunting down the right build for a Minecraft server version.

## Quick start

Install Jarlet (Linux, MacOS):

```bash
curl -fsSL https://raw.githubusercontent.com/DevSnox/Jarlet/main/install.sh | bash
```

This downloads a verified release binary, sets it up under an isolated `jarlet` service user, and installs a `jarlet` launcher on your `PATH`. It's a one-time setup; the script asks for `sudo` itself where needed, so don't prefix it with `sudo`.

Create and start a server:

```bash
jarlet setup myserver
jarlet start myserver --accept-eula
```

`setup` materializes a server directory from the bundled default template (see below); `start` launches it, auto-running `setup` first if the server doesn't exist yet.

## Sources

| Kind | Supported                                    |
| --- |----------------------------------------------|
| Server software | Paper                                        |
| Plugins | Hangar, Spiget (SpigotMC), GitHub (Releases) |

Want another source? Open an issue.

## Commands

```
jarlet setup <name> [template-file]        Create a new server instance from a template
jarlet start <name> [--accept-eula] [--foreground]
                                            Start a server, setting it up first if needed
jarlet stop <name>                         Stop a running server instance
jarlet install <package> <version> <path>  Download and verify a server-software package directly

jarlet plugin add <name> <identifier> [--pin <version> | --channel <name>] [--source <hangar|spiget|github>]
                                            Declare and fetch a new plugin
jarlet plugin remove <name> <identifier>   Undeclare a plugin and delete its installed jar
jarlet plugin update <name> <identifier|*> Update one declared plugin, or all of them with *
jarlet plugin list <name>                  List declared plugins and their installed state
jarlet plugin track <name> <identifier> [--pin <version> | --channel <name> | --track minor|patch]
                                            Change a declared plugin's update policy

jarlet track <name> [--pin <version> | --channel <name> | --track minor|patch]
                                            Change a server's update policy
```

Run any command with `--help` for its full option list.

## Configuration

Each server instance has its own `jarlet.toml`, generated from a template on `setup`. The bundled default template looks like this:

```toml
[template]
name = "default"
description = "Default Jarlet server template"

[server]
minecraft_version = "26.2"
memory = "2G"
port = 25565
online_mode = true
package = "paper"

[server.policy]
channel = "Release"
track = "channel"

[[plugins]]
id = "WorldEdit"
source = "hangar"

[plugins.policy]
channel = "Release"
track = "channel"
```

`policy` controls how updates are picked: `track = "channel"` follows a named release channel (`channel = "..."`), `track = "minor"`/`"patch"` stays within a semver bound, and `pin = "<exact version>"` locks to one version. `[server].policy` and each plugin's `[plugins.policy]` work the same way. Pass your own template file to `jarlet setup <name> <template-file>` to start from something other than the default.

Change a policy after the fact with `jarlet track`/`jarlet plugin track` instead of hand-editing `jarlet.toml`, e.g. `jarlet plugin track myserver WorldEdit --track minor`.

---

## Optional environment variables

Jarlet installs and starts a server with no configuration required. Set these (in `/etc/jarlet/jarlet.env`, pre-populated with commented examples after install, or exported in your shell) to unlock extra behavior — note the default template declares Hangar plugins, so `JARLET_HANGAR_API_KEY` is needed the first time you actually fetch one:

- `JARLET_GITHUB_TOKEN` — raises GitHub's API rate limit for GitHub-sourced plugins.
- `JARLET_HANGAR_API_KEY` — required to fetch Hangar-sourced plugins (Hangar requires auth even for reads).
- `JARLET_HOME` — overrides where Jarlet keeps its own state (default: `~/.jarlet`).
- `JARLET_SERVERS_DIR` — overrides where server instances are created (must be an absolute path).
- `JARLET_CHANNEL` (installer only) — pick a release channel: `alpha`, `beta`, `rc`, or `release` (default).
- `JARLET_TAG` (installer only) — pin installation to an exact release tag instead of a channel.

## Versioning

See the [versioning guide](versioning-guide.md).
