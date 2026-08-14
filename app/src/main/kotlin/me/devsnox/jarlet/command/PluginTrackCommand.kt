package me.devsnox.jarlet.command

import me.devsnox.jarlet.command.lib.JarletCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import me.devsnox.jarlet.Log
import me.devsnox.jarlet.command.lib.serverCommandBody
import me.devsnox.jarlet.service.PluginService

/**
 * `jarlet plugin track <name> <identifier> [--pin <version> | --channel <name> | --track minor|patch]`
 * -- changes a single declared plugin's policy after the fact, the
 * plugin-scoped counterpart to [TrackCommand] (which does the same for a
 * server's own `[server.policy]`). Validation and the toml rewrite are
 * [PluginService.track]'s job; this command is just a Clikt-to-service
 * translation plus rendering the returned [PluginService.PluginTrackResult].
 */
class PluginTrackCommand : JarletCommand(name = "track") {

    override fun help(context: Context) = "Change a declared plugin's update-tracking policy."

    private val name by argument(name = "name", help = "Server name (a directory under the servers root).")
    private val identifier by argument(name = "identifier", help = "The plugin's declared id.")

    private val pin by option("--pin", help = "Pin to an exact version.")
    private val channel by option("--channel", help = "Track this release channel.")
    private val track by option("--track", help = "Track within a semver bound: \"minor\" or \"patch\".")

    override fun run() = serverCommandBody {
        val result = PluginService.track(name, identifier, pin, channel, track)
        Log.info("""Tracking "${result.id}" (${result.source}): ${result.policyDisplay}""")
    }
}
