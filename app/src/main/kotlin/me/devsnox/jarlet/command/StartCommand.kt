package me.devsnox.jarlet.command

import me.devsnox.jarlet.command.lib.JarletCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import me.devsnox.jarlet.Log
import me.devsnox.jarlet.command.lib.serverCommandBody
import me.devsnox.jarlet.service.JarletServiceException
import me.devsnox.jarlet.service.ServerService

/**
 * `jarlet start <name> [template-file] [--foreground] [--accept-eula]`
 *
 * [ServerService.prepareStart] does everything both modes share (auto-setup,
 * validation, the EULA gate, installing/reinstalling the jar, routing
 * plugins, the already-running guard) and hands back the launch command.
 * From there this command owns process handling directly, since it differs
 * completely by mode and (for `--foreground`) is inherently about *this*
 * process's own stdio/exit code -- not something [ServerService] has an
 * equivalent of. Foreground mode runs the JVM child process to completion
 * and exits this process with the same status code -- the JVM has no true
 * process-image-replace primitive, so this is the closest equivalent to
 * exec-ing straight into the server process.
 */
class StartCommand : JarletCommand(name = "start") {
    override fun help(context: Context) = "Start a server instance, setting it up first if needed."

    private val name: String by argument(name = "name")
    private val templateFile: String? by argument(name = "template-file").optional()
    private val foreground: Boolean by option("--foreground").flag()
    private val acceptEula: Boolean by option("--accept-eula").flag()

    override fun run() = serverCommandBody {
        val preparation = ServerService.prepareStart(name, templateFile, acceptEula)

        if (foreground) {
            val process = ProcessBuilder(preparation.command)
                .directory(preparation.serverDir.toFile())
                .inheritIO()
                .start()
            throw ProgramResult(process.waitFor())
        }

        when (val outcome = ServerService.startBackground(preparation)) {
            is ServerService.ServerBackgroundStartOutcome.Started -> {
                Log.info("Server \"$name\" started with PID ${outcome.result.pid}")
                Log.info("Logs: ${outcome.result.logFile}")
            }
            is ServerService.ServerBackgroundStartOutcome.Failed -> {
                // Left as a direct CliktCommand.echo(..., err = true), not
                // Log -- these are raw lines tailed from the crashed
                // server's own log file, not a Jarlet-authored message, so
                // none of Log's functions (each either silent by default or
                // prefix-adding) is a faithful fit without changing this
                // output's actual content.
                outcome.failure.crashLogTail?.forEach { echo(it, err = true) }
                throw JarletServiceException.OperationFailed("Paper stopped during startup")
            }
        }
    }
}
