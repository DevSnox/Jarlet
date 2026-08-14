package me.devsnox.jarlet.command

import me.devsnox.jarlet.command.lib.JarletCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import me.devsnox.jarlet.Log
import me.devsnox.jarlet.command.lib.serverCommandBody
import me.devsnox.jarlet.service.ServerService

/**
 * `jarlet track <name> [--pin <version> | --channel <name> | --track minor|patch]`
 * -- changes a server's `[server].policy` after the fact.
 *
 * The top-level (server-scoped) counterpart to [PluginTrackCommand], which
 * does the same thing for a single declared plugin. Validation and the
 * toml rewrite are [ServerService.track]'s job; this command is just a
 * Clikt-to-service translation plus rendering the returned
 * [ServerService.ServerTrackResult].
 */
class TrackCommand : JarletCommand(name = "track") {

    override fun help(context: Context) = "Change a server's update-tracking policy."

    private val name by argument(name = "name", help = "Server name (a directory under the servers root).")

    private val pin by option("--pin", help = "Pin to an exact version.")
    private val channel by option("--channel", help = "Track this release channel.")
    private val track by option("--track", help = "Track within a semver bound: \"minor\" or \"patch\".")

    override fun run() = serverCommandBody {
        val result = ServerService.track(name, pin, channel, track)
        Log.info("""Server "$name" now tracks: ${result.policyDisplay}""")
    }
}
