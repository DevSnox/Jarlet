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

Clone this repo. Go into jarlet.conf and change configurations if needed.
Standart settings:
```conf
# jarlet.conf
DIR=first-server
MINECRAFT_VERSION=26.2
MEMORY=1G
PORT=25565
ONLINE_MODE=true
```

```bash
cd src
./start.sh --accept-eula --foreground
```

# Versioning
[Versioning guide](versioning-guide.md)
