package me.devsnox.jarlet.plugin

import java.nio.file.Path
import me.devsnox.jarlet.Log
import me.devsnox.jarlet.config.JarletToml

/**
 * Kotlin port of `src/plugin/router.sh`'s routing/looping responsibility
 * (not its adapter-*loading* mechanism -- that's [AdapterRegistry], the
 * static map replacing `load_adapter()`'s dynamic sourcing). Decides which
 * registered [PluginSourceAdapter] a declared entry's `source` maps to and
 * calls it; owns the "process every declared plugin" (`run_update_all()`)
 * and "process one declared plugin" (`run_update_one()`) loops. Full
 * identifier resolution for the latter (`resolve.sh`'s
 * `resolve_declared_identifier()`) is [SourceResolver.resolveDeclaredIdentifier],
 * which [routeOne] calls directly.
 */
object PluginRouter {

    /**
     * Routes a single declared entry ([source]/[id]/[policy]) to its
     * source adapter via [AdapterRegistry]. Kotlin equivalent of
     * `route_plugin()`. If no adapter is registered for [source], mirrors
     * `route_plugin()`'s behavior exactly: prints a skip message via
     * [Log.info] and returns normally (not an error -- an unregistered
     * source in a declared entry is expected today, since no adapters are
     * implemented until phase 4).
     */
    fun route(
        serverDir: Path,
        pluginsDir: Path,
        source: String,
        id: String,
        policy: JarletToml.Plugin.Policy,
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
     * Routes every entry in [declared]. Kotlin equivalent of
     * `run_update_all()`, including its "bare invocation with no
     * subcommand" / "explicit `update` with no target" behavior of
     * processing everything, and its "No plugins declared" message when
     * [declared] is empty.
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
            route(serverDir, pluginsDir, entry.source, entry.id, entry.policy, trustRequested)
        }
    }

    /**
     * Routes exactly one declared entry, identified by [identifier]
     * matched against [toml]'s declared entries by `id` alone (ids are
     * expected to be globally unique per server -- same assumption
     * `resolve.sh`'s header documents). Kotlin equivalent of
     * `run_update_one()`: [identifier] is resolved via
     * [SourceResolver.resolveDeclaredIdentifier], the same machinery
     * `RemoveCommand` already uses, rather than a bespoke inline lookup.
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
