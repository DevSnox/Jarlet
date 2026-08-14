package me.devsnox.jarlet.command

import me.devsnox.jarlet.command.lib.JarletCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.mordant.table.ColumnWidth
import com.github.ajalt.mordant.table.table
import me.devsnox.jarlet.Log
import me.devsnox.jarlet.command.lib.serverCommandBody
import me.devsnox.jarlet.service.PluginService

/**
 * `jarlet plugin list <name> [--page <n> | --all]`
 *
 * Renders the merged declared/installed, paginated view
 * [PluginService.list] builds as a Mordant table -- this command does
 * nothing but call that and render its [PluginService.PluginListResult].
 */
class ListCommand : JarletCommand(name = "list") {

    override fun help(context: Context) = "List plugins declared for a server, merged with their installed state."

    private val name by argument(name = "name", help = "Server name (a directory under the servers root).")

    private val page by option("--page", help = "1-indexed page number (see PLUGIN_LIST_PAGE_SIZE in jarlet-sys.conf). Defaults to 1.")
        .int()
        .default(1)

    private val all by option("--all", help = "Show every declared plugin, bypassing pagination.")
        .flag(default = false)

    override fun run() = serverCommandBody {
        val result = PluginService.list(name, page, all)

        if (result.rows.isEmpty()) {
            Log.info("No plugins declared")
            return@serverCommandBody
        }

        val rendered = table {
            // Columns default to expanding proportionally to the detected
            // terminal width; under GraalVM native-image (no real tty),
            // that detection can come back as 0, collapsing every column
            // to zero-width content while still drawing full borders. Auto
            // sizes each column to its own content instead, independent of
            // terminal-width detection.
            column(0) { width = ColumnWidth.Auto }
            column(1) { width = ColumnWidth.Auto }
            column(2) { width = ColumnWidth.Auto }
            column(3) { width = ColumnWidth.Auto }
            header {
                row("ID", "Source", "Version", "Policy")
            }
            body {
                for (row in result.rows) {
                    row(row.id, row.sourceDisplay, row.versionName ?: "not installed", row.policyDisplay)
                }
            }
        }
        terminal.println(rendered)

        if (!result.all) {
            val footer = buildString {
                append("Page ${result.page} of ${result.totalPages} (${result.count} plugin(s) total)")
                if (result.page < result.totalPages) append(" -- use --page ${result.page + 1} for more")
            }
            Log.info(footer)
        }
    }
}
