package me.devsnox.jarlet.command

import me.devsnox.jarlet.command.lib.JarletCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import me.devsnox.jarlet.Log
import me.devsnox.jarlet.command.lib.ServerCommandException
import me.devsnox.jarlet.command.lib.resolvePluginCommandContext
import me.devsnox.jarlet.command.lib.serverCommandBody
import me.devsnox.jarlet.config.JarletToml
import me.devsnox.jarlet.config.write
import me.devsnox.jarlet.plugin.SourceResolver

/**
 * `jarlet plugin track <name> <identifier> [--pin <version> | --channel <name> | --track minor|patch]`
 * -- changes a single declared plugin's policy after the fact, the
 * plugin-scoped counterpart to [TrackCommand] (which does the same for a
 * server's own `[server].policy`).
 *
 * `identifier` is resolved against the currently declared `[[plugins]]`
 * entries the same way [RemoveCommand]/[UpdateCommand] do, via
 * [SourceResolver.resolveDeclaredIdentifier] -- case-insensitive, and
 * validates the plugin is actually declared before anything is rewritten.
 * Only the matched entry's `policy` is replaced; every other declared
 * plugin is left untouched. Same full-rewrite tradeoff as [AddCommand]/
 * [RemoveCommand]/[UpdateCommand]/[TrackCommand].
 */
class PluginTrackCommand : JarletCommand(name = "track") {

    override fun help(context: Context) = "Change a declared plugin's update-tracking policy."

    private val name by argument(name = "name", help = "Server name (a directory under the servers root).")
    private val identifier by argument(name = "identifier", help = "The plugin's declared id.")

    private val pin by option("--pin", help = "Pin to an exact version.")
    private val channel by option("--channel", help = "Track this release channel.")
    private val track by option("--track", help = "Track within a semver bound: \"minor\" or \"patch\".")

    override fun run() = serverCommandBody {
        val optionCount = listOfNotNull(pin, channel, track).size
        if (optionCount != 1) {
            throw ServerCommandException("Exactly one of --pin, --channel, or --track is required")
        }
        if (track != null && track != "minor" && track != "patch") {
            throw ServerCommandException("--track must be \"minor\" or \"patch\"")
        }

        val (_, tomlFile, toml) = resolvePluginCommandContext(name)

        val resolved = SourceResolver.resolveDeclaredIdentifier(toml, identifier, "track")

        val newPolicy = when {
            pin != null -> JarletToml.Policy(pin = pin)
            channel != null -> JarletToml.Policy(track = "channel", channel = channel)
            else -> JarletToml.Policy(track = track)
        }

        val updatedToml = toml.copy(
            plugins = toml.plugins.map {
                if (it.source == resolved.source && it.id == resolved.id) it.copy(policy = newPolicy) else it
            },
        )

        Log.info("Note: this rewrites $tomlFile in full; hand-written comments and formatting are not preserved.")
        updatedToml.write(tomlFile)

        val policyDisplay = when {
            newPolicy.pin != null -> "pin: ${newPolicy.pin}"
            newPolicy.channel != null -> "channel: ${newPolicy.channel}"
            else -> "track: ${newPolicy.track}"
        }
        Log.info("""Tracking "${resolved.id}" (${resolved.source}): $policyDisplay""")
    }
}
