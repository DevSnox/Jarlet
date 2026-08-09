> Warning: Jarlet is under active development and pre BETA. Features, commands, and configuration formats may change or break. Do not use it for important production servers without backups.

# Jarlet
Jarlet is a lightweight package manager that builds and runs complete Minecraft servers from safe, versioned templates.

It downloads verified server software and plugins, manages runtime configuration, and starts a ready-to-play server with minimal setup.

# Sources

## Supported
- PaperMc

## Request
Please open an issue, if you'd like to request another source to be included.

# Usage

Clone this repo. Go into jarlet.toml and change configurations if needed.
Standart settings:
```toml
# jarlet.toml
[template]
name = "default"
description = "Default Jarlet server template"

[server]
minecraft_version = "26.2"
memory = "2G"
port = 25565
online_mode = true
```

```bash
cd src/server
./start.sh <server-name> --accept-eula --foreground
```

# Versioning
[Versioning guide](versioning-guide.md)
