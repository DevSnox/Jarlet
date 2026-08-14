package me.devsnox.jarlet.command

import com.github.ajalt.clikt.core.Context
import me.devsnox.jarlet.Log
import me.devsnox.jarlet.command.lib.JarletCommand
import me.devsnox.jarlet.config.EnvironmentStore
import me.devsnox.jarlet.instance.InstanceResolver

class EnvironmentListCommand : JarletCommand(name = "list") {
    override fun help(context: Context) = "List environment namespaces."

    override fun run() {
        val root = InstanceResolver.defaultRoot()
        val current = EnvironmentStore.current(root)
        EnvironmentStore.read(root).forEach { environment ->
            val marker = if (environment.name == current) " *" else ""
            Log.info("${environment.name}$marker")
        }
    }
}
