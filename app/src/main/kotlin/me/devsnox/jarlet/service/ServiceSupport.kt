package me.devsnox.jarlet.service

import java.nio.file.Files
import java.nio.file.Path
import me.devsnox.jarlet.config.JarletToml
import me.devsnox.jarlet.server.ServerPaths

/**
 * Shared "resolve a server by name, then load its jarlet.toml" preamble
 * needed by every service entry point that starts from a server name --
 * [PluginService]'s list/add/remove/update/track and [ServerService.track]
 * -- keeps the not-found/parse-failure messages consistent across all of
 * them, the service-layer counterpart of the CLI's former
 * `resolvePluginCommandContext`.
 */
internal data class ServerTomlContext(val serverDir: Path, val tomlFile: Path, val toml: JarletToml)

internal fun resolveServerToml(name: String): ServerTomlContext {
    val serverDir = ServerPaths.serverDir(name)
    if (!Files.isDirectory(serverDir)) {
        throw JarletServiceException.NotFound("No server named '$name' found at $serverDir")
    }

    val tomlFile = serverDir.resolve(ServerPaths.templateFilename())
    if (!Files.isRegularFile(tomlFile)) {
        throw JarletServiceException.NotFound("$tomlFile does not exist. Run setup (or start) for '$name' first to generate it.")
    }

    val toml = try {
        JarletToml.read(tomlFile)
    } catch (e: Exception) {
        throw JarletServiceException.InvalidInput("Could not parse $tomlFile as TOML: ${e.message}")
    }

    return ServerTomlContext(serverDir, tomlFile, toml)
}

/**
 * Renders a [JarletToml.Policy] as a short human-readable string --
 * `"pin: <version>"`, `"channel: <name>"`, `"track: minor"`/`"track:
 * patch"`, or `"-"` for no policy at all. Shared by [PluginService]'s
 * `list`/`track` results and [ServerService.track]'s result so all three
 * agree on one rendering instead of three near-identical `when` blocks.
 */
internal fun policyDisplay(policy: JarletToml.Policy): String = when {
    policy.pin != null -> "pin: ${policy.pin}"
    policy.channel != null -> "channel: ${policy.channel}"
    policy.track == "minor" || policy.track == "patch" -> "track: ${policy.track}"
    else -> "-"
}
