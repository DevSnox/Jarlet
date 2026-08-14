package me.devsnox.jarlet.command

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import me.devsnox.jarlet.Log
import me.devsnox.jarlet.command.lib.JarletCommand
import me.devsnox.jarlet.config.EnvironmentStore
import me.devsnox.jarlet.instance.InstanceResolver

class EnvironmentCreateCommand : JarletCommand(name = "create") {
    override fun help(context: Context) = "Create an environment namespace."

    private val name by argument(name = "name")

    override fun run() {
        EnvironmentStore.create(InstanceResolver.defaultRoot(), name)
        Log.info("Created environment '$name'")
    }
}
