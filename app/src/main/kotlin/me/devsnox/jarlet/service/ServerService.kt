package me.devsnox.jarlet.service

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import me.devsnox.jarlet.Log
import me.devsnox.jarlet.adapter.server.ServerSoftwareAdapters
import me.devsnox.jarlet.config.InstalledServer
import me.devsnox.jarlet.config.JarletToml
import me.devsnox.jarlet.config.ServerStateStore
import me.devsnox.jarlet.config.SysConfig
import me.devsnox.jarlet.config.write
import me.devsnox.jarlet.plugin.PluginDependencyChecker
import me.devsnox.jarlet.plugin.PluginRouter
import me.devsnox.jarlet.server.ServerPaths
import me.devsnox.jarlet.server.ServerSetup

/**
 * Transport-agnostic entry point for every server-lifecycle operation --
 * [me.devsnox.jarlet.command.SetupCommand]/[me.devsnox.jarlet.command.StartCommand]/
 * [me.devsnox.jarlet.command.StopCommand]/[me.devsnox.jarlet.command.TrackCommand]
 * are thin wrappers around these, and any future MCP/REST frontend would
 * call the same functions. Delegates to [ServerSetup]/[ServerSoftwareAdapters]/
 * [ServerStateStore]/[ServerPaths] for the actual work.
 *
 * `--foreground` process handling (inheriting this process's stdio, waiting
 * for the child, and propagating its exit code) deliberately stays out of
 * here and in [me.devsnox.jarlet.command.StartCommand] instead: it's about
 * *this process's* own exit behavior, not something an MCP tool call or an
 * HTTP request has an equivalent of. [prepareStart] does everything both
 * modes share; [startBackground] does the rest for the non-foreground path.
 */
object ServerService {

    private val MEMORY_PATTERN = Regex("^[1-9][0-9]*[MG]$")
    private val VERSION_PATTERN = Regex("^[0-9A-Za-z._-]+$")

    /** Everything [me.devsnox.jarlet.command.StartCommand] needs to actually launch the process, foreground or not. */
    data class ServerStartPreparation(val serverDir: Path, val toml: JarletToml, val command: List<String>)

    /** The result of a confirmed-alive background start. */
    data class ServerBackgroundStartResult(val pid: Long, val logFile: Path)

    /** The process died before [startBackground]'s startup-check delay elapsed. [crashLogTail] is the last lines of `logs/latest.log`, if it exists. */
    data class ServerStartupFailure(val logFile: Path, val crashLogTail: List<String>?)

    sealed class ServerBackgroundStartOutcome {
        data class Started(val result: ServerBackgroundStartResult) : ServerBackgroundStartOutcome()
        data class Failed(val failure: ServerStartupFailure) : ServerBackgroundStartOutcome()
    }

    data class ServerStopResult(val name: String, val pid: Long)

    data class ServerTrackResult(val policy: JarletToml.Policy, val policyDisplay: String, val tomlFile: Path)

    data class ServerPackageReconcileResult(
        val packageName: String,
        val minecraftVersion: String,
        val serverJar: Path,
        val installed: Boolean,
    )

    /** Materializes a new server instance directory from a template. See [ServerSetup.ensure]. */
    internal fun setup(name: String, templateFile: String?): ServerSetup.Result = ServerSetup.ensure(name, templateFile)

    /** Reconciles only the declared server package; runtime settings are untouched. */
    fun reconcilePackage(name: String): ServerPackageReconcileResult {
        ServerPaths.validateName(name)
        val serverDir = ServerPaths.serverDir(name)
        val config = serverDir.resolve(ServerPaths.templateFilename())
        if (!Files.isRegularFile(config)) {
            throw JarletServiceException.NotFound("$config does not exist")
        }
        val toml = ServerSetup.readToml(config)
        val server = toml.server
        val adapter = ServerSoftwareAdapters.find(server.packageInfo.name)
        val serverJar = serverDir.resolve("server.jar")
        val installed = ServerStateStore.read(serverDir)
        val needsInstall = !Files.isRegularFile(serverJar) ||
            installed == null || installed.pkg != server.packageInfo.name || installed.minecraftVersion != server.packageInfo.version
        if (needsInstall) {
            Files.createDirectories(serverDir)
            val installedVersion = adapter.install(server.packageInfo.version, serverJar, server.policy)
            ServerStateStore.write(serverDir, InstalledServer(pkg = server.packageInfo.name, minecraftVersion = installedVersion))
        }
        if (!Files.isRegularFile(serverJar)) {
            throw JarletServiceException.OperationFailed("server.jar installation failed")
        }
        return ServerPackageReconcileResult(server.packageInfo.name, server.packageInfo.version, serverJar, needsInstall)
    }

    /**
     * Everything a `start` needs before actually launching the process:
     * auto-setup if the instance doesn't exist yet, `[server.runtime].memory`/
     * `[server.package].version` validation, the EULA gate, installing/
     * reinstalling `server.jar` on drift, routing every declared plugin,
     * and the "already running" guard. Returns the launch command and the
     * directory to launch it from; throws [JarletServiceException] on any
     * failure along the way.
     */
    fun prepareStart(name: String, templateFile: String?, acceptEula: Boolean): ServerStartPreparation {
        ServerPaths.validateName(name)

        val templateName = ServerPaths.templateFilename()
        val serverDir = ServerPaths.serverDir(name)
        val config = serverDir.resolve(templateName)

        if (!Files.isRegularFile(config)) {
            ServerSetup.ensure(name, templateFile)
        }

        val toml = ServerSetup.readToml(config)
        val server = toml.server

        if (!MEMORY_PATTERN.matches(server.runtime.memory)) {
            throw JarletServiceException.InvalidInput("[server.runtime].memory must look like 2G or 2048M")
        }
        if (!VERSION_PATTERN.matches(server.packageInfo.version)) {
            throw JarletServiceException.InvalidInput("Invalid [server.package].version")
        }

        val adapter = ServerSoftwareAdapters.find(server.packageInfo.name)

        val eulaFile = serverDir.resolve("eula.txt")
        val eulaAccepted = Files.isRegularFile(eulaFile) &&
            Files.readAllLines(eulaFile).any { it == "eula=true" }
        if (!eulaAccepted) {
            if (!acceptEula) {
                throw JarletServiceException.InvalidInput(
                    "Run jarlet start $name --accept-eula after reading https://aka.ms/MinecraftEULA",
                )
            }
            Files.createDirectories(serverDir)
            Files.writeString(eulaFile, "eula=true\n")
        }

        val serverJar = serverDir.resolve("server.jar")
        val installedServer = ServerStateStore.read(serverDir)
        val serverDrifted = installedServer == null ||
            installedServer.pkg != server.packageInfo.name ||
            installedServer.minecraftVersion != server.packageInfo.version
        val jarMissing = !Files.isRegularFile(serverJar)

        if (jarMissing || serverDrifted) {
            if (!jarMissing && serverDrifted) {
                Log.info("jarlet.toml no longer matches the installed server.jar (was ${installedServer?.pkg} ${installedServer?.minecraftVersion}); reinstalling")
            }
            val installedVersion = adapter.install(server.packageInfo.version, serverJar, server.policy)
            ServerStateStore.write(serverDir, InstalledServer(pkg = server.packageInfo.name, minecraftVersion = installedVersion))
        }
        if (!Files.isRegularFile(serverJar)) {
            throw JarletServiceException.OperationFailed("server.jar installation failed")
        }

        val pluginsDir = serverDir.resolve("plugins")
        Files.createDirectories(pluginsDir)
        PluginRouter.routeAll(serverDir, pluginsDir, toml.plugins, trustRequested = false)
        var currentToml = toml
        for (entry in toml.plugins) {
            try {
                currentToml = PluginDependencyChecker.checkAndResolve(
                    serverDir, pluginsDir, config, currentToml, entry.source, entry.id,
                    resolveDependencies = false, trustRequested = false,
                ).toml
            } catch (e: Exception) {
                Log.info("""Failed to check/resolve dependencies for "${entry.id}" (${entry.source}): ${e.message}, continuing""")
            }
        }

        val jarletDir = serverDir.resolve(".jarlet")
        Files.createDirectories(jarletDir)
        val pidFile = jarletDir.resolve("server.pid")

        if (Files.isRegularFile(pidFile)) {
            val existingPid = Files.readString(pidFile).trim().toLongOrNull()
            if (existingPid != null && ProcessHandle.of(existingPid).map { it.isAlive }.orElse(false)) {
                throw JarletServiceException.Conflict("Server is already running with PID $existingPid")
            }
            Files.deleteIfExists(pidFile)
        }

        Log.info("Starting Paper ${server.packageInfo.version} with ${server.runtime.memory} memory")

        val command = listOf(
            "java",
            "-Xms${server.runtime.memory}",
            "-Xmx${server.runtime.memory}",
            "-Dfile.encoding=UTF-8",
            "-jar",
            "server.jar",
            "nogui",
        )

        return ServerStartPreparation(serverDir, currentToml, command)
    }

    /**
     * Launches [preparation]'s command detached (stdio redirected, not
     * inherited), records its PID, waits `STARTUP_CHECK_DELAY_SECONDS`,
     * and confirms it's still alive. Never throws for the process having
     * died during that check -- that's a normal-shaped outcome the caller
     * must inspect ([ServerBackgroundStartOutcome.Failed]) to decide how to
     * present it (the CLI echoes the crash-log tail to stderr; a
     * non-terminal caller would just read [ServerStartupFailure.crashLogTail]).
     */
    fun startBackground(preparation: ServerStartPreparation): ServerBackgroundStartOutcome {
        val serverDir = preparation.serverDir
        val jarletDir = serverDir.resolve(".jarlet")
        Files.createDirectories(jarletDir)
        val pidFile = jarletDir.resolve("server.pid")

        val process = ProcessBuilder(preparation.command)
            .directory(serverDir.toFile())
            .redirectInput(File("/dev/null"))
            .redirectOutput(File("/dev/null"))
            .redirectErrorStream(true)
            .start()

        val serverPid = process.pid()
        Files.writeString(pidFile, "$serverPid\n")

        val startupCheckDelaySeconds = SysConfig.default().value("STARTUP_CHECK_DELAY_SECONDS").toLongOrNull()
            ?.takeIf { it >= 0 }
            ?: throw JarletServiceException.OperationFailed("STARTUP_CHECK_DELAY_SECONDS must be a non-negative integer")

        Thread.sleep(startupCheckDelaySeconds * 1000)

        val logFile = serverDir.resolve("logs/latest.log")

        if (!process.isAlive) {
            Files.deleteIfExists(pidFile)

            val crashLogTail = if (Files.isRegularFile(logFile)) Files.readAllLines(logFile).takeLast(30) else null
            return ServerBackgroundStartOutcome.Failed(ServerStartupFailure(logFile, crashLogTail))
        }

        return ServerBackgroundStartOutcome.Started(ServerBackgroundStartResult(serverPid, logFile))
    }

    /** Stops a running server instance, waiting up to `STOP_TIMEOUT_SECONDS` for it to exit. */
    fun stop(name: String): ServerStopResult {
        ServerPaths.validateName(name)

        val serverDir = ServerPaths.serverDir(name)
        if (!Files.isDirectory(serverDir)) {
            throw JarletServiceException.NotFound("No server named '$name' found at $serverDir")
        }

        val pidFile = serverDir.resolve(".jarlet/server.pid")
        if (!Files.isRegularFile(pidFile)) {
            throw JarletServiceException.Conflict("Server is not running")
        }

        val serverPid = Files.readString(pidFile).trim().toLongOrNull()
            ?: throw JarletServiceException.OperationFailed("Invalid server PID")

        val handle = ProcessHandle.of(serverPid).orElse(null)
        if (handle == null || !handle.isAlive) {
            Files.deleteIfExists(pidFile)
            throw JarletServiceException.Conflict("Server is not running; removed stale PID file")
        }

        val info = handle.info()
        val commandLine = info.commandLine().orElseGet {
            (listOf(info.command().orElse("")) + info.arguments().orElse(emptyArray())).joinToString(" ")
        }
        if (!commandLine.contains("java") || !commandLine.contains("-jar server.jar")) {
            throw JarletServiceException.Conflict("PID $serverPid does not appear to be the Paper server")
        }

        Log.info("Stopping server \"$name\"...")
        handle.destroy()

        val stopTimeoutSeconds = SysConfig.default().value("STOP_TIMEOUT_SECONDS").toIntOrNull()
            ?.takeIf { it > 0 }
            ?: throw JarletServiceException.OperationFailed("STOP_TIMEOUT_SECONDS must be a positive integer")

        for (attempt in 1..stopTimeoutSeconds) {
            if (!handle.isAlive) {
                Files.deleteIfExists(pidFile)
                return ServerStopResult(name, serverPid)
            }
            Thread.sleep(1000)
        }

        throw JarletServiceException.OperationFailed("Server did not stop within $stopTimeoutSeconds seconds; inspect logs/latest.log")
    }

    /**
     * Changes [name]'s `[server.policy]`. Exactly one of [pin]/[channel]/
     * [track] is required; [track], if given, must be `"minor"` or
     * `"patch"`.
     */
    fun track(name: String, pin: String?, channel: String?, track: String?): ServerTrackResult {
        val optionCount = listOfNotNull(pin, channel, track).size
        if (optionCount != 1) {
            throw JarletServiceException.InvalidInput("Exactly one of --pin, --channel, or --track is required")
        }
        if (track != null && track != "minor" && track != "patch") {
            throw JarletServiceException.InvalidInput("--track must be \"minor\" or \"patch\"")
        }

        val (_, tomlFile, toml) = resolveServerToml(name)

        val newPolicy = when {
            pin != null -> JarletToml.Policy(pin = pin)
            channel != null -> JarletToml.Policy(track = "channel", channel = channel)
            else -> JarletToml.Policy(track = track)
        }

        val updatedToml = toml.copy(server = toml.server.copy(policy = newPolicy))

        Log.info("Note: this rewrites $tomlFile in full; hand-written comments and formatting are not preserved.")
        updatedToml.write(tomlFile)

        return ServerTrackResult(newPolicy, policyDisplay(newPolicy), tomlFile)
    }
}
