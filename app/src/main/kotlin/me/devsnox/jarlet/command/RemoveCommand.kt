package me.devsnox.jarlet.command

import me.devsnox.jarlet.command.lib.JarletCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import me.devsnox.jarlet.Log
import me.devsnox.jarlet.command.lib.resolvePluginCommandContext
import me.devsnox.jarlet.command.lib.serverCommandBody
import java.nio.file.Files
import me.devsnox.jarlet.config.JarletToml
import me.devsnox.jarlet.config.write
import me.devsnox.jarlet.config.PluginStateStore
import me.devsnox.jarlet.plugin.SourceResolver

/**
 * `jarlet plugin remove <name> <identifier>` -- undeclares a plugin and
 * deletes its installed jar.
 *
 * `identifier` is resolved against the currently declared `[[plugins]]`
 * entries by id alone, via [me.devsnox.jarlet.plugin.SourceResolver.resolveDeclaredIdentifier]
 * -- ids are globally unique per server (enforced at declare time by
 * [me.devsnox.jarlet.plugin.SourceResolver.checkIdAvailable]), so no
 * separate `source` argument is needed.
 *
 * A full uninstall: drops the `[[plugins]]` entry from `jarlet.toml` (a
 * full rewrite -- see [JarletToml]'s write docs), deletes the installed jar
 * from `plugins/` if [PluginStateStore] has one on record, and clears the
 * `plugins-state.json` entry via [PluginStateStore.remove] -- in that
 * order, so a failure partway through leaves a predictable partial state
 * rather than an inconsistent one.
 */
class RemoveCommand : JarletCommand(name = "remove") {

    override fun help(context: Context) = "Undeclare a plugin and delete its installed jar."

    private val name by argument(name = "name", help = "Server name (a directory under the servers root).")
    private val identifier by argument(name = "identifier", help = "The plugin's declared id.")

    override fun run() = serverCommandBody {
        val (serverDir, tomlFile, toml) = resolvePluginCommandContext(name)

        val pluginsDir = serverDir.resolve("plugins")
        Files.createDirectories(pluginsDir)

        val resolved = SourceResolver.resolveDeclaredIdentifier(toml, identifier, "remove")
        val source = resolved.source
        val id = resolved.id

        val updatedToml = toml.copy(plugins = toml.plugins.filterNot { it.source == source && it.id == id })

        Log.info("Note: this rewrites $tomlFile in full; hand-written comments and formatting are not preserved.")
        updatedToml.write(tomlFile)

        val installed = PluginStateStore.read(serverDir, source, id)
        if (installed != null) {
            val jarFile = pluginsDir.resolve(installed.file)
            if (Files.isRegularFile(jarFile)) {
                Files.delete(jarFile)
                Log.info("Deleted $jarFile")
            }
        }

        PluginStateStore.remove(serverDir, source, id)

        Log.info("""Removed "$id" ($source) from $tomlFile""")
    }
}
