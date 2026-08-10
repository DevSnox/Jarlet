package me.devsnox.jarlet.command

import me.devsnox.jarlet.command.lib.JarletCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import me.devsnox.jarlet.Log
import me.devsnox.jarlet.command.lib.ServerCommandException
import me.devsnox.jarlet.command.lib.serverCommandBody
import me.devsnox.jarlet.config.SysConfig
import me.devsnox.jarlet.server.ServerPaths
import java.nio.file.Files

/**
 * `jarlet stop <name>` -- Kotlin port of `src/server/stop.sh`.
 *
 * Unlike the bash version (which deliberately keeps its own inline
 * `fail()`/`config_value()`/`sys_config_value()`/`servers_root()` copies
 * rather than sourcing `lib.sh`, since it has no TOML-config dependency of
 * its own), this reuses [me.devsnox.jarlet.server.ServerPaths]/[SysConfig] directly -- there's no
 * equivalent reason to duplicate that logic in Kotlin.
 */
class StopCommand : JarletCommand(name = "stop") {
    override fun help(context: Context) = "Stop a running server instance."

    private val name: String by argument(name = "name")

    override fun run() = serverCommandBody {
        ServerPaths.validateName(name)

        val serverDir = ServerPaths.serverDir(name)
        if (!Files.isDirectory(serverDir)) {
            throw ServerCommandException("No server named '$name' found at $serverDir")
        }

        val pidFile = serverDir.resolve(".jarlet/server.pid")
        if (!Files.isRegularFile(pidFile)) {
            throw ServerCommandException("Server is not running")
        }

        val serverPid = Files.readString(pidFile).trim().toLongOrNull()
            ?: throw ServerCommandException("Invalid server PID")

        val handle = ProcessHandle.of(serverPid).orElse(null)
        if (handle == null || !handle.isAlive) {
            Files.deleteIfExists(pidFile)
            throw ServerCommandException("Server is not running; removed stale PID file")
        }

        val info = handle.info()
        val commandLine = info.commandLine().orElseGet {
            (listOf(info.command().orElse("")) + info.arguments().orElse(emptyArray())).joinToString(" ")
        }
        if (!commandLine.contains("java") || !commandLine.contains("-jar server.jar")) {
            throw ServerCommandException("PID $serverPid does not appear to be the Paper server")
        }

        Log.info("Stopping server \"$name\"...")
        handle.destroy()

        val stopTimeoutSeconds = SysConfig.default().value("STOP_TIMEOUT_SECONDS").toIntOrNull()
            ?.takeIf { it > 0 }
            ?: throw ServerCommandException("STOP_TIMEOUT_SECONDS must be a positive integer")

        for (attempt in 1..stopTimeoutSeconds) {
            if (!handle.isAlive) {
                Files.deleteIfExists(pidFile)
                Log.info("Server stopped")
                return@serverCommandBody
            }
            Thread.sleep(1000)
        }

        throw ServerCommandException("Server did not stop within $stopTimeoutSeconds seconds; inspect logs/latest.log")
    }
}
