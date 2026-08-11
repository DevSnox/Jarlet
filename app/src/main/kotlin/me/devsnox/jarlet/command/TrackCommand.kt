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

/**
 * `jarlet track <name> [--pin <version> | --channel <name> | --track minor|patch]`
 * -- changes a server's `[server].policy` after the fact.
 *
 * The top-level (server-scoped) counterpart to [PluginTrackCommand], which
 * does the same thing for a single declared plugin. Like [AddCommand]/
 * [RemoveCommand]/[UpdateCommand], it resolves the server directory and
 * jarlet.toml itself (via [resolvePluginCommandContext], despite the name)
 * and rewrites the whole file on change.
 */
class TrackCommand : JarletCommand(name = "track") {

    override fun help(context: Context) = "Change a server's update-tracking policy."

    private val name by argument(name = "name", help = "Server name (a directory under the servers root).")

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

        val newPolicy = when {
            pin != null -> JarletToml.Policy(pin = pin)
            channel != null -> JarletToml.Policy(track = "channel", channel = channel)
            else -> JarletToml.Policy(track = track)
        }

        val updatedToml = toml.copy(server = toml.server.copy(policy = newPolicy))

        Log.info("Note: this rewrites $tomlFile in full; hand-written comments and formatting are not preserved.")
        updatedToml.write(tomlFile)

        val policyDisplay = when {
            newPolicy.pin != null -> "pin: ${newPolicy.pin}"
            newPolicy.channel != null -> "channel: ${newPolicy.channel}"
            else -> "track: ${newPolicy.track}"
        }
        Log.info("""Server "$name" now tracks: $policyDisplay""")
    }
}
