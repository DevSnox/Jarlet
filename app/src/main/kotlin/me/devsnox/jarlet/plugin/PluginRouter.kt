package me.devsnox.jarlet.plugin

import java.nio.file.Path
import me.devsnox.jarlet.Log
import me.devsnox.jarlet.config.JarletToml

/**
 * Decides which registered [PluginSourceAdapter] a declared entry's
 * `source` maps to and calls it; owns the "process every declared plugin"
 * ([routeAll]) and "process one declared plugin" ([routeOne]) loops.
 * Adapter lookup itself is [AdapterRegistry]'s job. Full identifier
 * resolution for [routeOne] is [SourceResolver.resolveDeclaredIdentifier].
 */
object PluginRouter {

    /**
     * Routes a single declared entry ([source]/[id]/[policy]) to its
     * source adapter via [AdapterRegistry]. If no adapter is registered
     * for [source], prints a skip message via [Log.info] and returns
     * normally rather than treating it as an error -- a stale or
     * hand-edited `jarlet.toml` entry naming an unregistered source is an
     * expected, non-fatal case.
     */
    fun route(
        serverDir: Path,
        pluginsDir: Path,
        source: String,
        id: String,
        policy: JarletToml.Policy,
        trustRequested: Boolean = false,
    ) {
        val adapter = AdapterRegistry.find(source)
        if (adapter == null) {
            Log.info("""Skipping "$id" ($source): no adapter is implemented for this source""")
            return
        }

        adapter.process(serverDir, pluginsDir, id, policy, trustRequested)
    }

    /**
     * Routes every entry in [declared], printing "No plugins declared" if
     * [declared] is empty.
     *
     * Each entry is routed independently: an adapter failure for one
     * (network failure, verification failure, an external-hosting gate,
     * etc.) is logged and skipped rather than propagated, so a single bad
     * plugin can't abort every entry after it in the bulk update -- the
     * same per-entry isolation [PluginDependencyChecker.checkAndResolve]
     * already applies to its own dependency-resolution loop.
     */
    fun routeAll(
        serverDir: Path,
        pluginsDir: Path,
        declared: List<JarletToml.Plugin>,
        trustRequested: Boolean = false,
    ) {
        if (declared.isEmpty()) {
            Log.info("No plugins declared")
            return
        }

        for (entry in declared) {
            try {
                route(serverDir, pluginsDir, entry.source, entry.id, entry.policy, trustRequested)
            } catch (e: Exception) {
                Log.info("""Failed to update "${entry.id}" (${entry.source}): ${e.message}, continuing""")
            }
        }
    }

    /**
     * Routes exactly one declared entry, identified by [identifier]
     * matched against [toml]'s declared entries by `id` alone (ids are
     * globally unique per server). [identifier] is resolved via
     * [SourceResolver.resolveDeclaredIdentifier], the same machinery
     * [me.devsnox.jarlet.command.RemoveCommand] uses.
     */
    fun routeOne(
        serverDir: Path,
        pluginsDir: Path,
        toml: JarletToml,
        identifier: String,
        trustRequested: Boolean = false,
    ) {
        val resolved = SourceResolver.resolveDeclaredIdentifier(toml, identifier, "update")
        val entry = toml.plugins.first { it.source == resolved.source && it.id == resolved.id }

        route(serverDir, pluginsDir, entry.source, entry.id, entry.policy, trustRequested)
    }
}
