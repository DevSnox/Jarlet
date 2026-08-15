> [!WARNING]
> Jarlet is an early alpha. Commands and configuration may change. Back up important servers before testing.
[![Build native release](https://github.com/DevSnox/Jarlet/actions/workflows/build-native.yml/badge.svg)](https://github.com/DevSnox/Jarlet/actions/workflows/build-native.yml)

# Jarlet

Jarlet is a lightweight package manager for Minecraft servers. Describe a server once in a `jarlet.toml` template — software, version, plugins, update policy — and Jarlet builds it, keeps it in sync, and runs it.

No more manually re-downloading jars after every plugin update, or hunting down the right build for a Minecraft server version.

## Quick start

Install Jarlet (Linux, MacOS):

No stable release exists yet, so the alpha channel is set explicitly:

```bash
curl -fsSL https://raw.githubusercontent.com/DevSnox/Jarlet/main/install.sh | JARLET_CHANNEL=alpha bash
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
| Server software | Paper, Velocity                              |
| Plugins | Hangar, Spiget (SpigotMC), GitHub (Releases) |

Want another source? Open an issue.

## Commands

```
jarlet setup <name|environment/name> [template-file]
                                            Create a new server instance from a template
jarlet start <name> [--accept-eula] [--foreground]
                                            Start a server, setting it up first if needed
jarlet stop <name>                         Stop a running server instance
jarlet install <package-version> [target] [package]
                                            Download and verify a server-software package directly

jarlet plugin add <name> <identifier> [--pin <version> | --channel <name>] [--source <hangar|spiget|github>]
                                            Declare and fetch a new plugin
jarlet plugin remove <name> <identifier>   Undeclare a plugin and delete its installed jar
jarlet plugin update <name> <identifier|*> Update one declared plugin, or all of them with *
jarlet plugin list <name>                  List declared plugins and their installed state
jarlet plugin track <name> <identifier> [--pin <version> | --channel <name> | --track minor|patch]
                                            Change a declared plugin's update policy

jarlet track <name> [--pin <version> | --channel <name> | --track minor|patch]
                                            Change a server's update policy
jarlet copy <source> <target> <selector>...  Copy selected packages or worlds
jarlet copy-environment <source> <target> <selector>...
                                            Copy matching instances between environments
jarlet env create <name>                    Create an environment namespace
jarlet env remove <name>                    Remove an empty environment namespace
jarlet env use <name>                       Select the active environment
jarlet env list                             List environment namespaces
jarlet env current                          Show the active environment
```

Run any command with `--help` for its full option list.

## Configuration

Each instance has its own `jarlet.toml`, generated from a template on `setup`. Instances are stored under `~/.jarlet/instances/<environment>/<name>`; the implicit environment is `default`, and `jarlet env use <name>` selects the environment for plain instance names. Explicit names such as `prod/survival` always select that environment. `environments.toml` and `topology.toml` live at the Jarlet home root, beside `instances/`. The bundled default template looks like this:

```toml
[template]
name = "my-server"
description = "My Paper server"

[server.package]
name = "paper"
version = "26.2"

[server.policy]
channel = "Release"
track = "channel"

[server.runtime]
memory = "2G"
port = 25565
online_mode = true

[[plugins]]
id = "WorldEdit"
source = "hangar"

[plugins.policy]
channel = "Release"
track = "channel"
```

`policy` controls how updates are picked: `track = "channel"` follows a named release channel (`channel = "..."`), `track = "minor"`/`"patch"` stays within a semver bound, and `pin = "<exact version>"` locks to one version. `[server.policy]` and each plugin's `[plugins.policy]` work the same way.

Paper and Velocity packages are downloaded through the shared PaperMC
repository adapter. Paper versions refer to Minecraft versions, while
Velocity versions refer to the Velocity release itself:

```toml
[server.package]
name = "velocity"
version = "4.1.0-SNAPSHOT"
```

Manage environment namespaces with `jarlet env`. `default` is always
available as the virtual environment; use `jarlet env use default` to return
to it. The active non-default environment is stored in the root-level
`environments.toml`; removing an environment never deletes instance files and
is refused while configured instances remain:

```bash
jarlet env create prod
jarlet env use prod
jarlet env current
jarlet env list
jarlet env remove prod
```

Pass your own template file to start from something other than the default:

```bash
jarlet setup my-server ./jarlet.toml
jarlet start my-server --accept-eula --foreground
```

Change a policy after the fact with `jarlet track`/`jarlet plugin track` instead of hand-editing `jarlet.toml`, e.g. `jarlet plugin track myserver WorldEdit --track minor`.

The service layer also supports selective local state transfers between
instances using selectors such as `data.world.*`, `package.plugin.*`, and
`package.server`. Plugin transfers reconcile through Jarlet's package resolver
rather than copying JARs or state files directly. Optional root-level `environments.toml` and `topology.toml` files
describe environment metadata and desired proxy backends; runtime proxy
registration is reconciled through the configured proxy provider.

A topology definition can use global namespaced backend names or
environment-local names:

```toml
[topology]
proxy = "prod/proxy"
scope = "environment"

[[topology.backends]]
instance = "prod/survival"
address = "127.0.0.1:25566"
```

Resource selectors map to package declarations or existing Minecraft world
directories. `data.world.<name>` replaces a positively validated world
directory. No Jarlet-specific data or package directories are created.

For example:

```bash
jarlet copy prod/survival test/survival package.plugin.LuckPerms data.world.world
jarlet copy prod/survival test/survival package.server --resolved-pin
jarlet copy-environment prod test package.plugin.* data.world.*
```

## What Jarlet handles

- Versioned server templates
- Verified Paper downloads
- Foreground and background server processes
- Plugin add, list, update, pin, and removal
- Missing plugin dependency warnings
- Checksum verification where available and explicit trust for unknown external hosts

Explore every command:

```bash
jarlet --help
jarlet plugin --help
```

---

## Optional environment variables

Jarlet installs and starts a server with no configuration required. Set these (in `/etc/jarlet/jarlet.env`, pre-populated with commented examples after install, or exported in your shell) to unlock extra behavior — note the default template declares Hangar plugins, so `JARLET_HANGAR_API_KEY` is needed the first time you actually fetch one:

- `JARLET_GITHUB_TOKEN` — raises GitHub's API rate limit for GitHub-sourced plugins.
- `JARLET_HANGAR_API_KEY` — required to fetch Hangar-sourced plugins (Hangar requires auth even for reads).
- `JARLET_HOME` — overrides where Jarlet keeps its own state (default: `~/.jarlet`).
- `JARLET_INSTANCES_DIR` — overrides the `instances/` directory (must be an absolute path).
- `JARLET_CHANNEL` (installer only) — pick a release channel: `alpha`, `beta`, `rc`, or `release` (default).
- `JARLET_TAG` (installer only) — pin installation to an exact release tag instead of a channel.

## Versioning

Jarlet follows semantic versioning through `alpha`, `beta`, `rc`, and `stable`. See the [versioning guide](versioning-guide.md).
