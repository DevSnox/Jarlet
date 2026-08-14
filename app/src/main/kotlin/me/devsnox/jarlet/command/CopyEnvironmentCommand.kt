package me.devsnox.jarlet.command

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import me.devsnox.jarlet.Log
import me.devsnox.jarlet.command.lib.JarletCommand
import me.devsnox.jarlet.command.lib.serverCommandBody
import me.devsnox.jarlet.instance.ResourceSelector
import me.devsnox.jarlet.service.CopyService
import me.devsnox.jarlet.service.PackageTransferMode

/** `jarlet copy-environment <source> <target> <selector>...`. */
class CopyEnvironmentCommand : JarletCommand(name = "copy-environment") {
    override fun help(context: Context) = "Copy selected resources for matching instances between environments."

    private val source by argument(name = "source-environment")
    private val target by argument(name = "target-environment")
    private val selectors by argument(name = "selector").multiple()
    private val resolvedPin by option(
        "--resolved-pin",
        help = "Copy currently resolved package versions as exact pins.",
    ).flag()

    override fun run() = serverCommandBody {
        val result = CopyService.copyEnvironment(
            sourceEnvironment = source,
            targetEnvironment = target,
            selectors = selectors.map(ResourceSelector::parse).toSet(),
            packageMode = if (resolvedPin) PackageTransferMode.RESOLVED_PIN else PackageTransferMode.POLICY,
        )
        Log.info("Copied ${result.instances.size} instance(s) from $source to $target")
    }
}
