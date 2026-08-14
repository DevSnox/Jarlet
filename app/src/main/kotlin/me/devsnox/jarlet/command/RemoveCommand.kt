package me.devsnox.jarlet.command

import me.devsnox.jarlet.command.lib.JarletCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import me.devsnox.jarlet.Log
import me.devsnox.jarlet.command.lib.serverCommandBody
import me.devsnox.jarlet.service.PluginService

/**
 * `jarlet plugin remove <name> <identifier>` -- undeclares a plugin and
 * deletes its installed jar. The actual uninstall sequence is
 * [PluginService.remove]'s job; this command is just a Clikt-to-service
 * translation plus rendering the returned [PluginService.PluginRemoveResult].
 */
class RemoveCommand : JarletCommand(name = "remove") {

    override fun help(context: Context) = "Undeclare a plugin and delete its installed jar."

    private val name by argument(name = "name", help = "Server name (a directory under the servers root).")
    private val identifier by argument(name = "identifier", help = "The plugin's declared id.")

    override fun run() = serverCommandBody {
        val result = PluginService.remove(name, identifier)
        result.deletedJarFile?.let { Log.info("Deleted $it") }
        Log.info("""Removed "${result.id}" (${result.source}) from ${result.tomlFile}""")
    }
}
