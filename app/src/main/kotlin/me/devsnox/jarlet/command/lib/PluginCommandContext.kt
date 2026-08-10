package me.devsnox.jarlet.command.lib

import java.nio.file.Files
import java.nio.file.Path
import me.devsnox.jarlet.config.JarletToml
import me.devsnox.jarlet.server.ServerPaths

/**
 * Shared "resolve a server by name, then load its jarlet.toml" preamble
 * duplicated identically across [me.devsnox.jarlet.command.ListCommand], [me.devsnox.jarlet.command.AddCommand],
 * [me.devsnox.jarlet.command.RemoveCommand], and [me.devsnox.jarlet.command.UpdateCommand] -- extracted once so the same
 * server-not-found/toml-not-found/parse-failure error messages don't drift
 * across the four call sites.
 */
internal data class PluginCommandContext(val serverDir: Path, val tomlFile: Path, val toml: JarletToml)

internal fun resolvePluginCommandContext(name: String): PluginCommandContext {
    val serverDir = ServerPaths.serverDir(name)
    if (!Files.isDirectory(serverDir)) {
        throw ServerCommandException("No server named '$name' found at $serverDir")
    }

    val tomlFile = serverDir.resolve(ServerPaths.templateFilename())
    if (!Files.isRegularFile(tomlFile)) {
        throw ServerCommandException("$tomlFile does not exist. Run setup (or start) for '$name' first to generate it.")
    }

    val toml = try {
        JarletToml.read(tomlFile)
    } catch (e: Exception) {
        throw ServerCommandException("Could not parse $tomlFile as TOML: ${e.message}")
    }

    return PluginCommandContext(serverDir, tomlFile, toml)
}
