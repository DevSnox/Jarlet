package me.devsnox.jarlet.service

import java.nio.file.Files
import java.nio.file.Path
import me.devsnox.jarlet.Log
import me.devsnox.jarlet.config.JarletToml
import me.devsnox.jarlet.config.PluginStateStore
import me.devsnox.jarlet.config.SysConfig
import me.devsnox.jarlet.config.write
import me.devsnox.jarlet.plugin.AdapterRegistry
import me.devsnox.jarlet.plugin.PluginDeclarer
import me.devsnox.jarlet.plugin.PluginDependencyChecker
import me.devsnox.jarlet.plugin.PluginRouter
import me.devsnox.jarlet.plugin.SourceResolver

/**
 * Transport-agnostic entry point for every `jarlet plugin ...` operation --
 * [me.devsnox.jarlet.command.PluginCommand]'s subcommands are thin
 * wrappers around these functions, and any future MCP/REST frontend would
 * call the same ones. The actual work is delegated to [PluginDeclarer],
 * [PluginRouter], [PluginDependencyChecker], and [SourceResolver]; this
 * object's own job is turning plain input into validated calls into those,
 * and collecting their output into a return value instead of printing it,
 * so a caller other than a terminal can use it.
 */
object PluginService {

    /** One merged row of [list]'s result: a declared `[[plugins]]` entry joined with its installed state, if any. */
    data class PluginRow(
        val id: String,
        val sortId: String,
        val source: String,
        val sourceDisplay: String,
        val versionName: String?,
        val policyDisplay: String,
    )

    data class PluginListResult(
        val rows: List<PluginRow>,
        val page: Int,
        val totalPages: Int,
        val count: Int,
        val all: Boolean,
    )

    data class PluginAddResult(
        val source: String,
        val id: String,
        val tomlFile: Path,
        val dependencies: PluginDependencyChecker.DependencyCheckOutcome,
    )

    data class PluginRemoveResult(
        val source: String,
        val id: String,
        val tomlFile: Path,
        val deletedJarFile: Path?,
    )

    data class PluginUpdateResult(
        val tomlFile: Path,
        val dependencies: List<PluginDependencyChecker.DependencyCheckOutcome>,
    )

    data class PluginTrackResult(
        val source: String,
        val id: String,
        val tomlFile: Path,
        val policy: JarletToml.Policy,
        val policyDisplay: String,
    )

    /**
     * Merges [name]'s declared `[[plugins]]` entries with their installed
     * state and paginates the result. `all == true` bypasses pagination
     * entirely, regardless of [page]. A server with no declared plugins
     * always yields an empty [PluginListResult.rows], without validating
     * [page] against a page count that would be meaningless for zero rows.
     */
    fun list(name: String, page: Int, all: Boolean): PluginListResult {
        if (page < 1) {
            throw JarletServiceException.InvalidInput("--page must be a positive integer")
        }

        val (serverDir, _, toml) = resolveServerToml(name)
        val declared = toml.plugins
        val installed = PluginStateStore.readAll(serverDir)

        val merged = declared
            .map { d ->
                // Case-insensitive id match: declared and installed-state
                // ids should normally share the exact same casing, but
                // matching loosely here avoids silently showing a plugin
                // as "not installed" if that ever drifts.
                val i = installed.firstOrNull { it.source == d.source && it.id.equals(d.id, ignoreCase = true) }
                // Spiget's declared id is a bare numeric resource id;
                // prefer its cached display name once installed, keeping
                // the id alongside for disambiguation/scripting.
                val idDisplay = i?.displayName?.let { "$it (${d.id})" } ?: d.id
                PluginRow(
                    id = idDisplay,
                    sortId = d.id,
                    source = d.source,
                    sourceDisplay = AdapterRegistry.displayName(d.source),
                    versionName = i?.versionName,
                    policyDisplay = policyDisplay(d.policy),
                )
            }
            // Sorted by the raw declared id (not the display string above)
            // so caching a Spiget name doesn't reshuffle row order.
            .sortedWith(compareBy({ it.source }, { it.sortId }))

        if (merged.isEmpty()) {
            return PluginListResult(emptyList(), page = page, totalPages = 0, count = 0, all = all)
        }

        val pageSize = SysConfig.default().value("PLUGIN_LIST_PAGE_SIZE").toIntOrNull()?.takeIf { it >= 1 }
            ?: throw JarletServiceException.OperationFailed("PLUGIN_LIST_PAGE_SIZE in jarlet-sys.conf must be a positive integer")

        val count = merged.size

        // --all wins and bypasses pagination entirely, regardless of
        // whether --page was also given.
        val (start, end, totalPages) = if (all) {
            Triple(0, count, 1)
        } else {
            val computedTotalPages = (count + pageSize - 1) / pageSize
            if (page > computedTotalPages) {
                throw JarletServiceException.InvalidInput("Page $page does not exist (there are $computedTotalPages page(s))")
            }
            val computedStart = (page - 1) * pageSize
            Triple(computedStart, minOf(computedStart + pageSize, count), computedTotalPages)
        }

        return PluginListResult(merged.subList(start, end), page = page, totalPages = totalPages, count = count, all = all)
    }

    /**
     * Declares a new `[[plugins]]` entry for [name] and fetches it -- see
     * [PluginDeclarer.declareAndRoute] for the resolve/declare/write/route
     * sequence itself. [sourceOverride], if given, skips source inference
     * entirely. Exactly one of [pin]/[channel] may be given; with neither,
     * the declared entry tracks the "Release" channel.
     */
    fun add(
        name: String,
        identifier: String,
        pin: String?,
        channel: String?,
        sourceOverride: String?,
        trust: Boolean,
        resolveDependencies: Boolean,
    ): PluginAddResult {
        if (pin != null && channel != null) {
            throw JarletServiceException.InvalidInput("--pin and --channel are mutually exclusive")
        }

        val (serverDir, tomlFile, toml) = resolveServerToml(name)

        val pluginsDir = serverDir.resolve("plugins")
        Files.createDirectories(pluginsDir)

        val policy = if (pin != null) {
            JarletToml.Policy(pin = pin)
        } else {
            JarletToml.Policy(track = "channel", channel = channel ?: "Release")
        }

        val declaration = PluginDeclarer.declareAndRoute(
            serverDir, pluginsDir, tomlFile, toml, identifier, sourceOverride, policy, trust,
        )

        val dependencies = PluginDependencyChecker.checkAndResolve(
            serverDir, pluginsDir, tomlFile, declaration.toml, declaration.source, declaration.id, resolveDependencies, trust,
        )

        return PluginAddResult(declaration.source, declaration.id, tomlFile, dependencies)
    }

    /**
     * Undeclares [identifier] from [name] and deletes its installed jar: a
     * full uninstall -- drops the `[[plugins]]` entry from `jarlet.toml`,
     * deletes the installed jar from `plugins/` if recorded, and clears the
     * `plugins-state.json` entry -- in that order, so a failure partway
     * through leaves a predictable partial state.
     */
    fun remove(name: String, identifier: String): PluginRemoveResult {
        val (serverDir, tomlFile, toml) = resolveServerToml(name)

        val pluginsDir = serverDir.resolve("plugins")
        Files.createDirectories(pluginsDir)

        val resolved = SourceResolver.resolveDeclaredIdentifier(toml, identifier, "remove")
        val source = resolved.source
        val id = resolved.id

        val updatedToml = toml.copy(plugins = toml.plugins.filterNot { it.source == source && it.id == id })

        Log.info("Note: this rewrites $tomlFile in full; hand-written comments and formatting are not preserved.")
        updatedToml.write(tomlFile)

        val installed = PluginStateStore.read(serverDir, source, id)
        var deletedJarFile: Path? = null
        if (installed != null) {
            val jarFile = pluginsDir.resolve(installed.file)
            if (Files.isRegularFile(jarFile)) {
                Files.delete(jarFile)
                deletedJarFile = jarFile
            }
        }

        PluginStateStore.remove(serverDir, source, id)

        return PluginRemoveResult(source, id, tomlFile, deletedJarFile)
    }

    /**
     * Fetches the latest matching version for [identifier], or every
     * declared plugin if [identifier] is `"*"` (see [PluginRouter.routeAll]/
     * [PluginRouter.routeOne]). Each declared entry's dependency check runs
     * independently; a failure for one is logged and skipped rather than
     * propagated, same as the underlying routing.
     */
    fun update(name: String, identifier: String, trust: Boolean, resolveDependencies: Boolean): PluginUpdateResult {
        val (serverDir, tomlFile, toml) = resolveServerToml(name)

        val pluginsDir = serverDir.resolve("plugins")
        Files.createDirectories(pluginsDir)

        if (identifier == "*") {
            PluginRouter.routeAll(serverDir, pluginsDir, toml.plugins, trust)

            var currentToml = toml
            val outcomes = mutableListOf<PluginDependencyChecker.DependencyCheckOutcome>()
            for (entry in toml.plugins) {
                try {
                    val outcome = PluginDependencyChecker.checkAndResolve(
                        serverDir, pluginsDir, tomlFile, currentToml, entry.source, entry.id, resolveDependencies, trust,
                    )
                    currentToml = outcome.toml
                    outcomes += outcome
                } catch (e: Exception) {
                    Log.info("""Failed to check/resolve dependencies for "${entry.id}" (${entry.source}): ${e.message}, continuing""")
                }
            }
            return PluginUpdateResult(tomlFile, outcomes)
        }

        PluginRouter.routeOne(serverDir, pluginsDir, toml, identifier, trust)

        val resolved = SourceResolver.resolveDeclaredIdentifier(toml, identifier, "update")
        val outcome = PluginDependencyChecker.checkAndResolve(
            serverDir, pluginsDir, tomlFile, toml, resolved.source, resolved.id, resolveDependencies, trust,
        )
        return PluginUpdateResult(tomlFile, listOf(outcome))
    }

    /**
     * Changes [identifier]'s declared update-tracking policy. Exactly one
     * of [pin]/[channel]/[track] is required; [track], if given, must be
     * `"minor"` or `"patch"`.
     */
    fun track(name: String, identifier: String, pin: String?, channel: String?, track: String?): PluginTrackResult {
        val optionCount = listOfNotNull(pin, channel, track).size
        if (optionCount != 1) {
            throw JarletServiceException.InvalidInput("Exactly one of --pin, --channel, or --track is required")
        }
        if (track != null && track != "minor" && track != "patch") {
            throw JarletServiceException.InvalidInput("--track must be \"minor\" or \"patch\"")
        }

        val (_, tomlFile, toml) = resolveServerToml(name)

        val resolved = SourceResolver.resolveDeclaredIdentifier(toml, identifier, "track")

        val newPolicy = when {
            pin != null -> JarletToml.Policy(pin = pin)
            channel != null -> JarletToml.Policy(track = "channel", channel = channel)
            else -> JarletToml.Policy(track = track)
        }

        val updatedToml = toml.copy(
            plugins = toml.plugins.map {
                if (it.source == resolved.source && it.id == resolved.id) it.copy(policy = newPolicy) else it
            },
        )

        Log.info("Note: this rewrites $tomlFile in full; hand-written comments and formatting are not preserved.")
        updatedToml.write(tomlFile)

        return PluginTrackResult(resolved.source, resolved.id, tomlFile, newPolicy, policyDisplay(newPolicy))
    }
}
