package me.devsnox.jarlet.plugin

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import java.nio.file.Files
import me.devsnox.jarlet.config.JarletToml
import me.devsnox.jarlet.config.write
import me.devsnox.jarlet.server.ServerCommandException
import me.devsnox.jarlet.server.ServerPaths
import me.devsnox.jarlet.server.serverCommandBody

/**
 * `jarlet plugin remove <name> <identifier>` -- Kotlin port of
 * `src/plugin/commands.sh`'s `cmd_remove()`.
 *
 * `identifier` is resolved against the currently declared `[[plugins]]`
 * entries by id alone, via [SourceResolver.resolveDeclaredIdentifier] --
 * `source` is no longer a positional argument, since ids are globally
 * unique per server (enforced at declare time by
 * [SourceResolver.checkIdAvailable]).
 *
 * A full uninstall, matching `cmd_remove()` exactly: drops the
 * `[[plugins]]` entry from `jarlet.toml` (a full rewrite, same tradeoff as
 * `add`), deletes the installed jar from `plugins/` if
 * [PluginStateStore] has one on record, and clears the
 * `plugins-state.json` entry via [PluginStateStore.remove] -- all three,
 * in the same order as the bash version (toml rewrite, then jar deletion,
 * then state removal), so a failure partway through leaves the same kind
 * of partial state the bash version would.
 *
 * ## Integration status
 *
 * Fully wired and should work end-to-end today: no dependency on
 * [PluginRouter]/[AdapterRegistry]/phase 4 adapters at all (removal is
 * pure local bookkeeping, same as the bash version), and [JarletToml]'s
 * read/write path is on a working `tomlj`-backed implementation as of this
 * port (see [AddCommand]'s doc comment for that history).
 */
class RemoveCommand : CliktCommand(name = "remove") {

    override fun help(context: Context) = "Undeclare a plugin and delete its installed jar."

    private val name by argument(name = "name", help = "Server name (a directory under the servers root).")
    private val identifier by argument(name = "identifier", help = "The plugin's declared id.")

    override fun run() = serverCommandBody {
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

        val pluginsDir = serverDir.resolve("plugins")
        Files.createDirectories(pluginsDir)

        val resolved = SourceResolver.resolveDeclaredIdentifier(toml, identifier, "remove")
        val source = resolved.source
        val id = resolved.id

        val updatedToml = toml.copy(plugins = toml.plugins.filterNot { it.source == source && it.id == id })

        echo("Note: this rewrites $tomlFile in full; hand-written comments and formatting are not preserved.")
        updatedToml.write(tomlFile)

        val installed = PluginStateStore.read(serverDir, source, id)
        if (installed != null) {
            val jarFile = pluginsDir.resolve(installed.file)
            if (Files.isRegularFile(jarFile)) {
                Files.delete(jarFile)
                echo("Deleted $jarFile")
            }
        }

        PluginStateStore.remove(serverDir, source, id)

        echo("""Removed "$id" ($source) from $tomlFile""")
    }
}
