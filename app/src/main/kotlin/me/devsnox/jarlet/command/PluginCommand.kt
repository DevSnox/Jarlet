package me.devsnox.jarlet.command

import me.devsnox.jarlet.command.lib.JarletCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

/**
 * Clikt group command housing the plugin subsystem's subcommands, in
 * `jarlet plugin <subcommand> <name> [...]` command-then-argument order.
 * Each subcommand ([ListCommand], [AddCommand], [RemoveCommand],
 * [UpdateCommand], [PluginTrackCommand]) resolves its own server
 * path/`jarlet.toml` directly (via [me.devsnox.jarlet.server.ServerPaths])
 * rather than sharing state through this group, since there's no
 * group-level "resolve once, share with subcommands" step worth the added
 * indirection over four small commands.
 *
 * Everything declared for a server is updated explicitly via `jarlet
 * plugin update <name> *` (see [UpdateCommand]) rather than any implicit
 * bare-invocation default.
 *
 * This group itself has no behavior of its own -- pure dispatch.
 */
class PluginCommand : JarletCommand(name = "plugin") {
    init {
        subcommands(ListCommand(), AddCommand(), RemoveCommand(), UpdateCommand(), PluginTrackCommand())
    }

    override fun help(context: Context) = "Manage plugins declared in a server's jarlet.toml."

    override fun run() = Unit
}
