package me.devsnox.jarlet.command

import me.devsnox.jarlet.command.lib.JarletCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import me.devsnox.jarlet.adapter.server.ServerSoftwareAdapters
import me.devsnox.jarlet.command.lib.serverCommandBody
import java.nio.file.Paths

/**
 * `jarlet install <minecraft-version> [target] [package]` -- Kotlin port
 * of `src/server/install.sh`.
 *
 * Kept as its own subcommand for parity/direct use, though [SetupCommand]
 * and [StartCommand] call the resolved
 * [me.devsnox.jarlet.adapter.server.ServerSoftwareAdapter] directly rather
 * than shelling out to this command the way `setup.sh`/`start.sh` invoke
 * `install.sh` as a subprocess.
 */
class InstallCommand : JarletCommand(name = "install") {
    override fun help(context: Context) = "Download and verify a server-software package."

    private val minecraftVersion: String by argument(name = "minecraft-version")
    private val target: String by argument(name = "target").default("server.jar")
    private val serverPackage: String by argument(name = "package").default("paper")

    override fun run() = serverCommandBody {
        val adapter = ServerSoftwareAdapters.find(serverPackage)
        adapter.install(minecraftVersion, Paths.get(target))
    }
}
