package me.devsnox.jarlet.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

/**
 * Clikt group command housing the plugin subsystem's subcommands --
 * Kotlin equivalent of `src/plugin/plugin.sh`'s `<name> <subcommand>`
 * dispatch, reshaped into Clikt's idiomatic `jarlet plugin <subcommand>
 * <name> [...]` command-then-argument order rather than a line-by-line
 * port of bash's `jarlet plugin <name> <subcommand>`. [ListCommand]
 * established this shape (phase 3); [AddCommand]/[RemoveCommand]/
 * [UpdateCommand] (phase 5, `src/plugin/commands.sh` + the dispatch part of
 * `plugin.sh`) follow it for consistency -- each resolves its own server
 * path/`jarlet.toml` directly (via [me.devsnox.jarlet.server.ServerPaths])
 * rather than sharing state through this group, same as [ListCommand]
 * does, since Clikt has no group-level "resolve once, share with
 * subcommands" step here worth the added indirection over four small
 * commands.
 *
 * bash's bare `plugins.sh <name>` (no subcommand at all) defaulting to
 * "update everything declared" has no natural equivalent in this
 * subcommand-first shape and is deliberately dropped -- `jarlet plugin
 * update <name>` (see [UpdateCommand]) is the equivalent, explicit form.
 *
 * This group itself has no behavior of its own (mirrors `plugin.sh`'s
 * `main()`, which is pure dispatch once past its shared setup).
 */
class PluginCommand : CliktCommand(name = "plugin") {
    init {
        subcommands(ListCommand(), AddCommand(), RemoveCommand(), UpdateCommand())
    }

    override fun help(context: Context) = "Manage plugins declared in a server's jarlet.toml."

    override fun run() = Unit
}
