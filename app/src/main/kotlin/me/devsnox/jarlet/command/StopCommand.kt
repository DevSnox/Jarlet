package me.devsnox.jarlet.command

import me.devsnox.jarlet.command.lib.JarletCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import me.devsnox.jarlet.Log
import me.devsnox.jarlet.command.lib.serverCommandBody
import me.devsnox.jarlet.service.ServerService

/**
 * `jarlet stop <name>`
 *
 * The stop/wait sequence itself is [ServerService.stop]'s job; this
 * command is just a Clikt-to-service translation plus the final
 * confirmation line.
 */
class StopCommand : JarletCommand(name = "stop") {
    override fun help(context: Context) = "Stop a running server instance."

    private val name: String by argument(name = "name")

    override fun run() = serverCommandBody {
        ServerService.stop(name)
        Log.info("Server stopped")
    }
}
