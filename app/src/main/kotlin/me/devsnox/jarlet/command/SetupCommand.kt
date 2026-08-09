package me.devsnox.jarlet.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import me.devsnox.jarlet.command.lib.serverCommandBody
import me.devsnox.jarlet.server.ServerSetup

/**
 * `jarlet setup <name> [template-file]` -- Kotlin port of `src/server/setup.sh`.
 *
 * Materializes a new server instance directory from a `jarlet.toml`-shaped
 * template; the actual work lives in [me.devsnox.jarlet.server.ServerSetup.ensure] so [StartCommand]
 * can reuse it for its own auto-setup-if-missing fallback.
 */
class SetupCommand : CliktCommand(name = "setup") {
    override fun help(context: Context) = "Create a new server instance from a template."

    private val name: String by argument(name = "name")
    private val templateFile: String? by argument(name = "template-file").optional()

    override fun run() = serverCommandBody {
        val result = ServerSetup.ensure(name, templateFile)
        echo("Server \"$name\" is ready at ${result.serverDir}")
        echo("Run: jarlet start $name")
    }
}
