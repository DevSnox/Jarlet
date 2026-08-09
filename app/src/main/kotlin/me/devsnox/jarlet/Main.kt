package me.devsnox.jarlet

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.versionOption
import me.devsnox.jarlet.config.JarletVersion
import me.devsnox.jarlet.command.PluginCommand
import me.devsnox.jarlet.command.InstallCommand
import me.devsnox.jarlet.command.SetupCommand
import me.devsnox.jarlet.command.StartCommand
import me.devsnox.jarlet.command.StopCommand

/**
 * Root command -- Kotlin equivalent of `src/jarlet`'s dispatcher.
 *
 * Phase 2 ("Server lifecycle vertical slice", see the migration plan) added
 * the `install`/`setup`/`start`/`stop` subcommands. Phase 3 ("Plugin
 * subsystem core") adds the `plugin` subcommand tree (currently just
 * `plugin list`, see [PluginCommand]/[me.devsnox.jarlet.command.ListCommand]
 * -- `add`/`remove`/`update` are phase 5).
 */
class Jarlet : CliktCommand(name = "jarlet") {

    init {
        versionOption(JarletVersion.VERSION)
        subcommands(InstallCommand(), SetupCommand(), StartCommand(), StopCommand(), PluginCommand())
    }

    override fun help(context: Context) =
        "Manage Paper/Minecraft servers and their plugins."

    override fun run() = Unit
}

fun main(args: Array<String>) = Jarlet().main(args)
