package me.devsnox.jarlet.command

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import me.devsnox.jarlet.Log
import me.devsnox.jarlet.command.lib.JarletCommand
import me.devsnox.jarlet.config.EnvironmentStore
import me.devsnox.jarlet.instance.InstanceResolver

class EnvironmentRemoveCommand : JarletCommand(name = "remove") {
    override fun help(context: Context) = "Remove an empty environment namespace."

    private val name by argument(name = "name")

    override fun run() {
        EnvironmentStore.remove(InstanceResolver.defaultRoot(), name)
        Log.info("Removed environment '$name'")
    }
}
