package me.devsnox.jarlet.server

import me.devsnox.jarlet.command.lib.ServerCommandException
import me.devsnox.jarlet.config.SysConfig
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Shared server-instance name/path helpers -- Kotlin port of the pieces of
 * `src/lib/lib.sh` (`servers_root()`, `template_filename()`, and the
 * `^[0-9A-Za-z._-]+$` name-validation regex repeated across
 * `install.sh`/`setup.sh`/`start.sh`/`stop.sh`) every server lifecycle
 * command needs.
 */
object ServerPaths {
    private val VALID_NAME = Regex("^[0-9A-Za-z._-]+$")

    /** Validates a server instance name, failing the way every bash script's inline check does. */
    fun validateName(name: String): String {
        if (!VALID_NAME.matches(name)) {
            throw ServerCommandException("Server name must be a simple name (letters, digits, ._-)")
        }
        return name
    }

    /** Mirrors `servers_root()`: `$JARLET_SERVERS_DIR` env override (must be absolute) if set, else `SERVERS_DIR_DEFAULT` from sys config, with `$HOME` expanded. */
    fun serversRoot(): Path {
        val override = System.getenv("JARLET_SERVERS_DIR")
        if (!override.isNullOrEmpty()) {
            val path = Paths.get(override)
            if (!path.isAbsolute) {
                throw ServerCommandException("JARLET_SERVERS_DIR must be an absolute path")
            }
            return path
        }

        val default = SysConfig.default().value("SERVERS_DIR_DEFAULT")
        val expanded = default.replace("\$HOME", System.getProperty("user.home"))
        return Paths.get(expanded)
    }

    /** The instance directory for a validated server [name] under [serversRoot]. */
    fun serverDir(name: String): Path = serversRoot().resolve(validateName(name))

    /** Mirrors `template_filename()`: the standard per-server template/instance filename (`jarlet.toml`). */
    fun templateFilename(): String = SysConfig.default().value("TEMPLATE_FILENAME")
}
