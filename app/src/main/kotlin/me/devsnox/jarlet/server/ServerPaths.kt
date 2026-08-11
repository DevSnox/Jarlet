package me.devsnox.jarlet.server

import me.devsnox.jarlet.command.lib.ServerCommandException
import me.devsnox.jarlet.config.SysConfig
import java.nio.file.Path
import java.nio.file.Paths

/** Shared server-instance name/path helpers every server lifecycle command needs. */
object ServerPaths {
    private val VALID_NAME = Regex("^[0-9A-Za-z_-]+$")

    /**
     * JVM-system-property override for [serversRoot], checked before the
     * `JARLET_SERVERS_DIR` env var. Real users/scripts have no reason to
     * ever set this -- the env var remains the one documented, real-world
     * override -- it exists purely so tests can point an entire command
     * invocation at a JUnit temp directory without touching the real
     * `~/jarlet/servers`. The JVM has no supported, portable way to mutate
     * `System.getenv()` at runtime, but `System.setProperty`/`clearProperty`
     * are ordinary, safe JVM APIs, which is why this is a system property
     * rather than an env-var-mutation hack.
     */
    internal const val SERVERS_DIR_PROPERTY = "jarlet.serversDir"

    /** Validates a server instance name. */
    fun validateName(name: String): String {
        if (!VALID_NAME.matches(name)) {
            throw ServerCommandException("Server name must be a simple name (letters, digits, _-)")
        }
        return name
    }

    /** [SERVERS_DIR_PROPERTY] system property (tests only) or `$JARLET_SERVERS_DIR` env override (must be absolute) if set, else `SERVERS_DIR_DEFAULT` from sys config, with `$HOME` expanded. */
    fun serversRoot(): Path {
        val override = System.getProperty(SERVERS_DIR_PROPERTY) ?: System.getenv("JARLET_SERVERS_DIR")
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

    /** The standard per-server template/instance filename (`jarlet.toml`). */
    fun templateFilename(): String = SysConfig.default().value("TEMPLATE_FILENAME")
}
