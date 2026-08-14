package me.devsnox.jarlet.command

import me.devsnox.jarlet.command.lib.JarletCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import me.devsnox.jarlet.Log
import me.devsnox.jarlet.command.lib.serverCommandBody
import me.devsnox.jarlet.service.ServerService

/**
 * `jarlet setup <name> [template-file]`
 *
 * Materializes a new server instance directory from a `jarlet.toml`-shaped
 * template; the actual work lives in [ServerService.setup] so
 * [StartCommand] can reuse it for its own auto-setup-if-missing fallback.
 */
class SetupCommand : JarletCommand(name = "setup") {
    override fun help(context: Context) = "Create a new server instance from a template."

    private val name: String by argument(name = "name")
    private val templateFile: String? by argument(name = "template-file").optional()

    override fun run() = serverCommandBody {
        val result = ServerService.setup(name, templateFile)
        Log.info("Server \"$name\" is ready at ${result.serverDir}")
        Log.info("Run: jarlet start $name")
    }
}
