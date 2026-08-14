package me.devsnox.jarlet.command

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import me.devsnox.jarlet.command.lib.JarletCommand

/** `jarlet env ...` environment namespace management. */
class EnvironmentCommand : JarletCommand(name = "env") {
    init {
        subcommands(
            EnvironmentCreateCommand(),
            EnvironmentRemoveCommand(),
            EnvironmentUseCommand(),
            EnvironmentListCommand(),
            EnvironmentCurrentCommand(),
        )
    }

    override fun help(context: Context) = "Manage environment namespaces."

    override fun run() = Unit
}
