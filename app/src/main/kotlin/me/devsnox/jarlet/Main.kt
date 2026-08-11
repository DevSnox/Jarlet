package me.devsnox.jarlet

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.mordant.terminal.Terminal
import me.devsnox.jarlet.config.JarletVersion
import me.devsnox.jarlet.command.PluginCommand
import me.devsnox.jarlet.command.InstallCommand
import me.devsnox.jarlet.command.SetupCommand
import me.devsnox.jarlet.command.StartCommand
import me.devsnox.jarlet.command.StopCommand
import me.devsnox.jarlet.command.TrackCommand

/** Root command: dispatches to the server lifecycle and plugin subcommands. */
class Jarlet : CliktCommand(name = "jarlet") {

    private val debug by option("--debug", help = "Print verbose diagnostic detail (HTTP calls, retries, timing).")
        .flag(default = false)

    init {
        versionOption(JarletVersion.VERSION)
        subcommands(InstallCommand(), SetupCommand(), StartCommand(), StopCommand(), TrackCommand(), PluginCommand())

        // Mordant's default Terminal() auto-detects the terminal width, which
        // resolves to 0 (or otherwise fails) under the GraalVM native-image
        // binary -- there's no real tty detection there. That collapses help
        // text, usage synopses, and error messages down to nearly nothing
        // (e.g. a bare "Error: missing argument <name>" with no Usage: line).
        // Force a sane fallback width once, at the root, so every subcommand
        // inherits it and all Mordant-rendered output stays readable.
        context {
            terminal = Terminal(width = 80)
        }
    }

    override fun help(context: Context) =
        "Manage Paper/Minecraft servers and their plugins."

    override fun run() {
        if (debug) Log.enableDebug()

        // Bare `jarlet` with no subcommand prints full help instead of
        // silently exiting.
        if (currentContext.invokedSubcommand == null) {
            throw PrintHelpMessage(currentContext)
        }
    }
}

fun main(args: Array<String>) = Jarlet().main(args)
