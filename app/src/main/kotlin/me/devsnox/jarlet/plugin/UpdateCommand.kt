package me.devsnox.jarlet.plugin

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import java.nio.file.Files
import me.devsnox.jarlet.config.JarletToml
import me.devsnox.jarlet.server.ServerCommandException
import me.devsnox.jarlet.server.serverCommandBody

/**
 * `jarlet plugin update <name> [<identifier>] [--trust]` -- Kotlin port of
 * `src/plugin/router.sh`'s `run_update_all()`/`run_update_one()` split, as
 * exposed through `plugin.sh`'s `update` subcommand in `commands.sh`'s
 * doc comments.
 *
 * With no `identifier`, updates every plugin declared in the server's
 * `jarlet.toml` ([PluginRouter.routeAll]) -- the Kotlin equivalent of both
 * `update` with no target *and* bash's bare `plugins.sh <name>` (no
 * subcommand at all) default, which this subcommand-first CLI shape folds
 * into one explicit form; see [PluginCommand]'s doc comment for why the
 * bare-invocation shortcut itself isn't reproduced.
 *
 * With an `identifier`, updates exactly that one declared plugin
 * ([PluginRouter.routeOne]), resolved against the declared entries by id
 * alone (source is not needed as CLI input -- ids are globally unique per
 * server).
 *
 * `--trust` may appear regardless of whether `identifier` is given,
 * mirroring `commands.sh`'s note that it "may appear anywhere alongside an
 * optional identifier" -- Clikt's option parsing handles this for free
 * (it's not positional), so there's no bespoke arg-loop needed here the
 * way bash's hand-rolled `while (( $# > 0 ))` required.
 *
 * Fully wired against [PluginRouter.routeAll]/[PluginRouter.routeOne] and
 * [AdapterRegistry]'s three registered adapters.
 */
class UpdateCommand : CliktCommand(name = "update") {

    override fun help(context: Context) = "Fetch the latest matching version for one or all declared plugins."

    private val name by argument(name = "name", help = "Server name (a directory under the servers root).")
    private val identifier by argument(name = "identifier", help = "Update only this declared plugin id (default: update all).").optional()

    private val trust by option("--trust", help = "Proceed past an external-hosting gate this adapter can't otherwise resolve.")
        .flag(default = false)

    override fun run() = serverCommandBody {
        val (serverDir, _, toml) = resolvePluginCommandContext(name)

        val pluginsDir = serverDir.resolve("plugins")
        Files.createDirectories(pluginsDir)

        val target = identifier
        if (target == null) {
            PluginRouter.routeAll(serverDir, pluginsDir, toml.plugins, trust, echo = { echo(it) })
        } else {
            PluginRouter.routeOne(serverDir, pluginsDir, toml.plugins, target, trust, echo = { echo(it) })
        }
    }
}
