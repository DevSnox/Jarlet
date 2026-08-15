package me.devsnox.jarlet.command

import me.devsnox.jarlet.command.lib.JarletCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import me.devsnox.jarlet.adapter.server.ServerSoftwareAdapters
import me.devsnox.jarlet.command.lib.serverCommandBody
import java.nio.file.Paths

/**
 * `jarlet install <package-version> [target] [package]`
 *
 * Kept as its own subcommand for direct use, though [SetupCommand] and
 * [StartCommand] call the resolved
 * [me.devsnox.jarlet.adapter.server.ServerSoftwareAdapter] directly rather
 * than invoking this command.
 */
class InstallCommand : JarletCommand(name = "install") {
    override fun help(context: Context) = "Download and verify a server-software package."

    private val packageVersion: String by argument(name = "package-version")
    private val target: String by argument(name = "target").default("server.jar")
    private val serverPackage: String by argument(name = "package").default("paper")

    override fun run() = serverCommandBody {
        val adapter = ServerSoftwareAdapters.find(serverPackage)
        adapter.install(serverPackage, packageVersion, Paths.get(target))
    }
}
