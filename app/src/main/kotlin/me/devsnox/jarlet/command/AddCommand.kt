package me.devsnox.jarlet.command

import me.devsnox.jarlet.command.lib.JarletCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import me.devsnox.jarlet.Log
import me.devsnox.jarlet.command.lib.serverCommandBody
import me.devsnox.jarlet.service.PluginService

/**
 * `jarlet plugin add <name> <identifier> [--pin <version> | --channel <name>] [--source <hangar|spiget|github>] [--trust]`
 *
 * `source` is inferred from `identifier` by
 * [me.devsnox.jarlet.plugin.SourceResolver.resolveAddIdentifier] (`owner/repo` -> github,
 * numeric -> probe hangar/spiget, name -> hangar exact slug then spiget
 * exact-name search), unless `--source` is given as an explicit escape
 * hatch that skips inference entirely (still validated via
 * [me.devsnox.jarlet.plugin.SourceResolver.validateSourceIdShape]).
 *
 * All validation, declaring, and fetching is [PluginService.add]'s job;
 * this command is just a Clikt-to-service translation plus rendering the
 * returned [PluginService.PluginAddResult].
 */
class AddCommand : JarletCommand(name = "add") {

    override fun help(context: Context) = "Declare and fetch a new plugin for a server."

    private val name by argument(name = "name", help = "Instance name or environment/instance.")
    private val identifier by argument(
        name = "identifier",
        help = "Hangar/Spiget slug or numeric id, an 'owner/repo' GitHub identifier, or (with --source) a raw source id.",
    )

    private val pin by option("--pin", help = "Pin to an exact version, instead of tracking a channel.")
    private val channel by option("--channel", help = "Track this release channel (default: Release).")
    private val sourceOverride by option(
        "--source",
        help = "Skip source inference; must be one of hangar, spiget, github.",
    )
    private val trust by option("--trust", help = "Proceed past an external-hosting gate this adapter can't otherwise resolve.")
        .flag(default = false)
    private val resolveDependencies by option(
        "--resolve-dependencies",
        help = "Automatically resolve and add this plugin's plugin.yml \"depend\" entries that aren't already declared.",
    ).flag(default = false)

    override fun run() = serverCommandBody {
        val result = PluginService.add(name, identifier, pin, channel, sourceOverride, trust, resolveDependencies)
        Log.info("""Declared "${result.id}" (${result.source}) in ${result.tomlFile}""")
    }
}
