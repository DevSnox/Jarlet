package me.devsnox.jarlet.command

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import me.devsnox.jarlet.Log
import me.devsnox.jarlet.command.lib.JarletCommand
import me.devsnox.jarlet.command.lib.serverCommandBody
import me.devsnox.jarlet.service.CopyService
import me.devsnox.jarlet.service.PackageTransferMode

/**
 * `jarlet copy <source> <target> <selector>...`
 *
 * Selectors are semantic resource names; the copy service owns their
 * resolution and transfer behavior. This command only translates CLI
 * arguments and renders results.
 */
class CopyCommand : JarletCommand(name = "copy") {
    override fun help(context: Context) = "Copy selected packages or worlds between instances."

    private val source by argument(name = "source", help = "Source instance, such as prod/survival.")
    private val target by argument(name = "target", help = "Target instance, such as test/survival.")
    private val selectors by argument(
        name = "selector",
        help = "Semantic selector: package.server, package.plugin.*, data.world.*, or all.",
    ).multiple()
    private val resolvedPin by option(
        "--resolved-pin",
        help = "Copy currently resolved package versions as exact pins.",
    ).flag()

    override fun run() = serverCommandBody {
        val result = CopyService.copy(
            source = source,
            target = target,
            selectors = selectors.toSet(),
            packageMode = if (resolvedPin) PackageTransferMode.RESOLVED_PIN else PackageTransferMode.POLICY,
        )
        Log.info("Copied ${result.results.size} resource(s) from $source to $target")
        result.results.forEach { resource ->
            Log.info("${resource.type}: ${resource.resourceId} (${resource.status.name.lowercase()})")
        }
    }
}
