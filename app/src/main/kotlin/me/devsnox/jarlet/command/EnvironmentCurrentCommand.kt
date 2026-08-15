package me.devsnox.jarlet.command

import com.github.ajalt.clikt.core.Context
import me.devsnox.jarlet.Log
import me.devsnox.jarlet.command.lib.JarletCommand
import me.devsnox.jarlet.config.EnvironmentStore
import me.devsnox.jarlet.instance.InstanceResolver

class EnvironmentCurrentCommand : JarletCommand(name = "current") {
    override fun help(context: Context) = "Show the active environment."

    override fun run() {
        val current = EnvironmentStore.current(InstanceResolver.defaultRoot())
        Log.info(current)
    }
}
