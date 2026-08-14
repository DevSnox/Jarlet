package me.devsnox.jarlet.command

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import me.devsnox.jarlet.Log
import me.devsnox.jarlet.command.lib.JarletCommand
import me.devsnox.jarlet.config.EnvironmentStore
import me.devsnox.jarlet.instance.InstanceResolver

class EnvironmentUseCommand : JarletCommand(name = "use") {
    override fun help(context: Context) = "Select the active environment."

    private val name by argument(name = "name")

    override fun run() {
        EnvironmentStore.use(InstanceResolver.defaultRoot(), name)
        Log.info("Using environment '$name'")
    }
}
