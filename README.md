> [!WARNING]
> Jarlet is an early alpha. Commands and configuration may change. Back up important servers before testing.
[![Build native release](https://github.com/DevSnox/Jarlet/actions/workflows/build-native.yml/badge.svg)](https://github.com/DevSnox/Jarlet/actions/workflows/build-native.yml)

# Jarlet

**Install and run minecraft servers and plugins from one config without manual handling.**

Jarlet installs and updates server package and plugins based on templates.

## Get started

Jarlet currently supports **Linux and MacOS**. You also need a Java runtime compatible with the Minecraft version you want to run.

### 1. Install Jarlet

Install the latest alpha release:

```bash
curl -fsSL https://raw.githubusercontent.com/DevSnox/Jarlet/main/install.sh \
  | env JARLET_CHANNEL=alpha bash
```

The installer:

- downloads the correct Linux binary from GitHub Releases;
- verifies its GitHub SHA-256 digest and published checksum;
- installs `jarlet` to `/usr/local/bin`;
- uses `sudo` only when the install directory requires it.

Confirm it is ready:

```bash
jarlet --version
```

### 2. Start your first server

```bash
jarlet start my-server --accept-eula --foreground
```

That one command creates the server under `~/jarlet/servers/my-server`, downloads the latest stable Paper build for the bundled template, writes the basic configuration, and starts Paper in your terminal.

Read the [Minecraft EULA](https://aka.ms/MinecraftEULA) before using `--accept-eula`.

Stop a foreground server with <kbd>Ctrl</kbd>+<kbd>C</kbd>. For a server started without `--foreground`, run:

```bash
jarlet stop my-server
```

## Use your own template

Create a `jarlet.toml` file:

```toml
[template]
name = "my-server"
description = "My Paper server"

[server]
package = "paper"
minecraft_version = "26.2"
memory = "2G"
port = 25565
online_mode = true
```

Then build the server from it:

```bash
jarlet setup my-server ./jarlet.toml
jarlet start my-server --accept-eula --foreground
```

## What Jarlet handles

- Versioned server templates
- Verified Paper downloads
- Foreground and background server processes
- Plugin add, list, update, pin, and removal
- Missing plugin dependency warnings
- Checksum verification where available and explicit trust for unknown external hosts

- Server software: **Paper**
- Plugin sources: **Hangar, Spiget, and GitHub Releases**

Explore every command:

```bash
jarlet --help
jarlet plugin --help
```

## Versioning

Jarlet follows semantic versioning through `alpha`, `beta`, `rc`, and `stable`. See the [versioning guide](versioning-guide.md).
