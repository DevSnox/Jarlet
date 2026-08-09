package me.devsnox.jarlet.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import me.devsnox.jarlet.adapter.server.ServerSoftwareAdapters
import me.devsnox.jarlet.command.lib.ServerCommandException
import me.devsnox.jarlet.command.lib.serverCommandBody
import me.devsnox.jarlet.config.SysConfig
import me.devsnox.jarlet.server.ServerPaths
import me.devsnox.jarlet.server.ServerSetup
import java.io.File
import java.nio.file.Files

/**
 * `jarlet start <name> [template-file] [--foreground] [--accept-eula]` --
 * Kotlin port of `src/server/start.sh`.
 *
 * Sets the instance up first (via [me.devsnox.jarlet.server.ServerSetup.ensure]) if it doesn't
 * already exist, same as `start.sh` shelling out to `setup.sh`. Foreground
 * mode approximates bash's `exec java ...` (which replaces the shell
 * process) by running the JVM child to completion and exiting this process
 * with the same status code -- the JVM has no true process-image-replace
 * primitive, so this is the closest equivalent.
 */
class StartCommand : CliktCommand(name = "start") {
    override fun help(context: Context) = "Start a server instance, setting it up first if needed."

    private val name: String by argument(name = "name")
    private val templateFile: String? by argument(name = "template-file").optional()
    private val foreground: Boolean by option("--foreground").flag()
    private val acceptEula: Boolean by option("--accept-eula").flag()

    override fun run() = serverCommandBody {
        ServerPaths.validateName(name)

        val templateName = ServerPaths.templateFilename()
        val serverDir = ServerPaths.serverDir(name)
        val config = serverDir.resolve(templateName)

        if (!Files.isRegularFile(config)) {
            ServerSetup.ensure(name, templateFile)
        }

        val toml = ServerSetup.readToml(config)
        val server = toml.server

        if (!MEMORY_PATTERN.matches(server.memory)) {
            throw ServerCommandException("[server].memory must look like 2G or 2048M")
        }
        if (!VERSION_PATTERN.matches(server.minecraftVersion)) {
            throw ServerCommandException("Invalid [server].minecraft_version")
        }

        val adapter = ServerSoftwareAdapters.find(server.pkg)

        val eulaFile = serverDir.resolve("eula.txt")
        val eulaAccepted = Files.isRegularFile(eulaFile) &&
                Files.readAllLines(eulaFile).any { it == "eula=true" }
        if (!eulaAccepted) {
            if (!acceptEula) {
                throw ServerCommandException(
                    "Run jarlet start $name --accept-eula after reading https://aka.ms/MinecraftEULA",
                )
            }
            Files.createDirectories(serverDir)
            Files.writeString(eulaFile, "eula=true\n")
        }

        val serverJar = serverDir.resolve("server.jar")
        if (!Files.isRegularFile(serverJar)) {
            adapter.install(server.minecraftVersion, serverJar)
        }
        if (!Files.isRegularFile(serverJar)) {
            throw ServerCommandException("server.jar installation failed")
        }

        val jarletDir = serverDir.resolve(".jarlet")
        Files.createDirectories(jarletDir)
        val pidFile = jarletDir.resolve("server.pid")

        if (Files.isRegularFile(pidFile)) {
            val existingPid = Files.readString(pidFile).trim().toLongOrNull()
            if (existingPid != null && ProcessHandle.of(existingPid).map { it.isAlive }.orElse(false)) {
                throw ServerCommandException("Server is already running with PID $existingPid")
            }
            Files.deleteIfExists(pidFile)
        }

        echo("Starting Paper ${server.minecraftVersion} with ${server.memory} memory")

        val command = listOf(
            "java",
            "-Xms${server.memory}",
            "-Xmx${server.memory}",
            "-Dfile.encoding=UTF-8",
            "-jar",
            "server.jar",
            "nogui",
        )

        if (foreground) {
            val process = ProcessBuilder(command)
                .directory(serverDir.toFile())
                .inheritIO()
                .start()
            throw ProgramResult(process.waitFor())
        }

        val process = ProcessBuilder(command)
            .directory(serverDir.toFile())
            .redirectInput(File("/dev/null"))
            .redirectOutput(File("/dev/null"))
            .redirectErrorStream(true)
            .start()

        val serverPid = process.pid()
        Files.writeString(pidFile, "$serverPid\n")

        val startupCheckDelaySeconds = SysConfig.default().value("STARTUP_CHECK_DELAY_SECONDS").toLongOrNull()
            ?.takeIf { it >= 0 }
            ?: throw ServerCommandException("STARTUP_CHECK_DELAY_SECONDS must be a non-negative integer")

        Thread.sleep(startupCheckDelaySeconds * 1000)

        if (!process.isAlive) {
            Files.deleteIfExists(pidFile)

            val logFile = serverDir.resolve("logs/latest.log")
            if (Files.isRegularFile(logFile)) {
                Files.readAllLines(logFile).takeLast(30).forEach { echo(it, err = true) }
            }

            throw ServerCommandException("Paper stopped during startup")
        }

        echo("Server \"$name\" started with PID $serverPid")
        echo("Logs: ${serverDir.resolve("logs/latest.log")}")
    }

    private companion object {
        val MEMORY_PATTERN = Regex("^[1-9][0-9]*[MG]$")
        val VERSION_PATTERN = Regex("^[0-9A-Za-z._-]+$")
    }
}
