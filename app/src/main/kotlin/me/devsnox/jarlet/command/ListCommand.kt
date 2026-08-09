package me.devsnox.jarlet.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.mordant.table.ColumnWidth
import com.github.ajalt.mordant.table.table
import me.devsnox.jarlet.command.lib.ServerCommandException
import me.devsnox.jarlet.command.lib.resolvePluginCommandContext
import me.devsnox.jarlet.command.lib.serverCommandBody
import me.devsnox.jarlet.config.JarletToml
import me.devsnox.jarlet.config.SysConfig
import me.devsnox.jarlet.plugin.AdapterRegistry
import me.devsnox.jarlet.plugin.PluginStateStore

/**
 * `jarlet plugin list <name> [--page <n> | --all]` -- Kotlin port of
 * `src/plugin/list.sh`'s `cmd_list()`.
 *
 * Combines the DECLARED `[[plugins]]` entries from a server's
 * `jarlet.toml` ([JarletToml.plugins]) with the INSTALLED state recorded
 * in `plugins-state.json` ([me.devsnox.jarlet.plugin.PluginStateStore.readAll]) into one merged,
 * paginated view, rendered as a Mordant table instead of `list.sh`'s
 * plain `printf` lines.
 *
 * Unlike `plugin.sh`'s single dispatcher (which resolves the server
 * directory/toml file/plugins JSON once in `main()` and threads them into
 * whichever subcommand ran), this command resolves its own server paths
 * directly (via [ServerPaths], shared with the server-lifecycle commands)
 * rather than through shared `PluginCommand`-level state -- see
 * [PluginCommand]'s header for why each subcommand (this one, and
 * `add`/`remove`/`update`) does its own resolution instead.
 */
class ListCommand : CliktCommand(name = "list") {

    override fun help(context: Context) = "List plugins declared for a server, merged with their installed state."

    private val name by argument(name = "name", help = "Server name (a directory under the servers root).")

    private val page by option("--page", help = "1-indexed page number (see PLUGIN_LIST_PAGE_SIZE in jarlet-sys.conf). Defaults to 1.")
        .int()
        .default(1)

    private val all by option("--all", help = "Show every declared plugin, bypassing pagination.")
        .flag(default = false)

    override fun run() = serverCommandBody {
        if (page < 1) {
            throw ServerCommandException("--page must be a positive integer")
        }

        val (serverDir, _, toml) = resolvePluginCommandContext(name)
        val declared = toml.plugins

        val installed = PluginStateStore.readAll(serverDir)

        val merged = declared
            .map { d ->
                // Case-insensitive id match: declared and installed-state
                // ids should normally share the exact same casing (both
                // come from the same adapter-resolved id at declare
                // time), but matching loosely here avoids silently
                // showing a plugin as "not installed" if that ever drifts.
                val i = installed.firstOrNull { it.source == d.source && it.id.equals(d.id, ignoreCase = true) }
                // Spiget's declared id is a bare numeric resource id (unlike
                // Hangar/GitHub-releases, whose id is already a readable
                // slug/name) -- once the plugin has actually been
                // installed/updated at least once, SpigetAdapter caches the
                // real resource name in the installed-state record
                // (i.displayName). Prefer that, keeping the id alongside for
                // disambiguation/scripting, same as e.g. github-releases'
                // "owner/repo" id already provides. Declared-but-never-
                // installed Spiget plugins have no state record yet, so
                // they still fall back to the bare id here -- expected, not
                // a bug (see PluginStateStore.InstalledVersion.displayName).
                val idDisplay = i?.displayName?.let { "$it (${d.id})" } ?: d.id
                MergedRow(
                    id = idDisplay,
                    sortId = d.id,
                    source = d.source,
                    sourceDisplay = AdapterRegistry.displayName(d.source),
                    versionName = i?.versionName,
                    policyDisplay = policyDisplay(d.policy),
                )
            }
            // Sorted by the raw declared id (not the display string above)
            // so caching a Spiget name doesn't reshuffle row order versus
            // today's behavior.
            .sortedWith(compareBy({ it.source }, { it.sortId }))

        if (merged.isEmpty()) {
            echo("No plugins declared")
            return@serverCommandBody
        }

        val pageSize = SysConfig.default().value("PLUGIN_LIST_PAGE_SIZE").toIntOrNull()?.takeIf { it >= 1 }
            ?: throw ServerCommandException("PLUGIN_LIST_PAGE_SIZE in jarlet-sys.conf must be a positive integer")

        val count = merged.size

        // Mirrors list.sh's actual behavior (not its doc comment, which
        // claims --page/--all are mutually exclusive but never enforces
        // it): --all simply wins and bypasses pagination entirely,
        // regardless of whether --page was also given.
        val (start, end, totalPages) = if (all) {
            Triple(0, count, 1)
        } else {
            val computedTotalPages = (count + pageSize - 1) / pageSize
            if (page > computedTotalPages) {
                throw ServerCommandException("Page $page does not exist (there are $computedTotalPages page(s))")
            }
            val computedStart = (page - 1) * pageSize
            Triple(computedStart, minOf(computedStart + pageSize, count), computedTotalPages)
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
                for (row in merged.subList(start, end)) {
                    row(row.id, row.sourceDisplay, row.versionName ?: "not installed", row.policyDisplay)
                }
            }
        }
        terminal.println(rendered)

        if (!all) {
            val footer = buildString {
                append("Page $page of $totalPages ($count plugin(s) total)")
                if (page < totalPages) append(" -- use --page ${page + 1} for more")
            }
            echo(footer)
        }
    }

    private data class MergedRow(
        val id: String,
        val sortId: String,
        val source: String,
        val sourceDisplay: String,
        val versionName: String?,
        val policyDisplay: String,
    )

    private fun policyDisplay(policy: JarletToml.Plugin.Policy): String = when {
        policy.pin != null -> "pin: ${policy.pin}"
        policy.channel != null -> "channel: ${policy.channel}"
        else -> "-"
    }
}
