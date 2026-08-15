package me.devsnox.jarlet.command

import me.devsnox.jarlet.command.lib.JarletCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import me.devsnox.jarlet.command.lib.serverCommandBody
import me.devsnox.jarlet.service.PluginService

/**
 * `jarlet plugin update <name> <identifier> [--trust]`
 *
 * With identifier `"*"`, updates every plugin declared in the server's
 * `jarlet.toml`; with an `identifier`, updates exactly that one declared
 * plugin. All routing/dependency-check output is emitted by
 * [PluginService.update] and the domain code it delegates to, so this
 * command needs no rendering of its own beyond that.
 */
class UpdateCommand : JarletCommand(name = "update") {

    override fun help(context: Context) = "Fetch the latest matching version for one or all declared plugins."

    private val name by argument(name = "name", help = "Instance name or environment/instance.")
    private val identifier by argument(name = "identifier", help = "Declared plugin id; use \"*\" to update all declared plugins.")

    private val trust by option("--trust", help = "Proceed past an external-hosting gate this adapter can't otherwise resolve.")
        .flag(default = false)
    private val resolveDependencies by option(
        "--resolve-dependencies",
        help = "Automatically resolve and add updated plugins' plugin.yml \"depend\" entries that aren't already declared.",
    ).flag(default = false)

    override fun run() = serverCommandBody {
        PluginService.update(name, identifier, trust, resolveDependencies)
    }
}
