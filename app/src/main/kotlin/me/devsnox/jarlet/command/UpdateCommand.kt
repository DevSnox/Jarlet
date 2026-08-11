package me.devsnox.jarlet.command

import me.devsnox.jarlet.command.lib.JarletCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import me.devsnox.jarlet.command.lib.resolvePluginCommandContext
import me.devsnox.jarlet.command.lib.serverCommandBody
import java.nio.file.Files
import me.devsnox.jarlet.Log
import me.devsnox.jarlet.plugin.PluginDependencyChecker
import me.devsnox.jarlet.plugin.PluginRouter
import me.devsnox.jarlet.plugin.SourceResolver

/**
 * `jarlet plugin update <name> <identifier> [--trust]`
 *
 * With identifier `"*"`, updates every plugin declared in the server's
 * `jarlet.toml` ([me.devsnox.jarlet.plugin.PluginRouter.routeAll]).
 *
 * With an `identifier`, updates exactly that one declared plugin
 * ([me.devsnox.jarlet.plugin.PluginRouter.routeOne]), resolved against the declared entries by id
 * alone (source is not needed as CLI input -- ids are globally unique per
 * server).
 *
 * `--trust` may appear regardless of whether `identifier` is given --
 * Clikt's option parsing handles this for free since it's not positional.
 *
 * Wired against [me.devsnox.jarlet.plugin.PluginRouter.routeAll]/[me.devsnox.jarlet.plugin.PluginRouter.routeOne] and
 * [me.devsnox.jarlet.plugin.AdapterRegistry]'s three registered adapters.
 */
class UpdateCommand : JarletCommand(name = "update") {

    override fun help(context: Context) = "Fetch the latest matching version for one or all declared plugins."

    private val name by argument(name = "name", help = "Server name (a directory under the servers root).")
    private val identifier by argument(name = "identifier", help = "Declared plugin id to update, or \"*\" to update everything declared.")

    private val trust by option("--trust", help = "Proceed past an external-hosting gate this adapter can't otherwise resolve.")
        .flag(default = false)
    private val resolveDependencies by option(
        "--resolve-dependencies",
        help = "Automatically resolve and add updated plugins' plugin.yml \"depend\" entries that aren't already declared.",
    ).flag(default = false)

    override fun run() = serverCommandBody {
        val (serverDir, tomlFile, toml) = resolvePluginCommandContext(name)

        val pluginsDir = serverDir.resolve("plugins")
        Files.createDirectories(pluginsDir)

        if (identifier == "*") {
            PluginRouter.routeAll(serverDir, pluginsDir, toml.plugins, trust)

            var currentToml = toml
            for (entry in toml.plugins) {
                try {
                    currentToml = PluginDependencyChecker.checkAndResolve(
                        serverDir, pluginsDir, tomlFile, currentToml, entry.source, entry.id, resolveDependencies, trust,
                    )
                } catch (e: Exception) {
                    Log.info("""Failed to check/resolve dependencies for "${entry.id}" (${entry.source}): ${e.message}, continuing""")
                }
            }
        } else {
            PluginRouter.routeOne(serverDir, pluginsDir, toml, identifier, trust)

            val resolved = SourceResolver.resolveDeclaredIdentifier(toml, identifier, "update")
            PluginDependencyChecker.checkAndResolve(
                serverDir, pluginsDir, tomlFile, toml, resolved.source, resolved.id, resolveDependencies, trust,
            )
        }
    }
}
