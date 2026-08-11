package me.devsnox.jarlet.plugin

import java.nio.file.Path
import me.devsnox.jarlet.Log
import me.devsnox.jarlet.config.JarletToml
import me.devsnox.jarlet.config.PluginStateStore
import me.devsnox.jarlet.io.PluginYamlReader

/**
 * Post-install `depend`/`softdepend` awareness for `plugin add`/`plugin
 * update` -- reads the freshly-installed jar's `plugin.yml`
 * (via [me.devsnox.jarlet.io.PluginYamlReader]) and compares its `depend`/`softdepend` names
 * against the server's currently-declared `[[plugins]]` ids.
 *
 * Matching is by exact name against declared ids only -- a `plugin.yml`
 * `depend`/`softdepend` name and a declared `jarlet.toml` id aren't
 * guaranteed to be the same string in general (e.g. `provides` aliases),
 * but resolving that properly is out of scope for this minimal
 * implementation; exact-name matching is what's asked for.
 *
 * Always warns (no flag needed) for anything missing. Only when
 * [resolveDependencies] is `true` does it also attempt to actually resolve
 * and install missing HARD (`depend`) entries -- never `softdepend`, since
 * by definition the plugin still works without those. Each dependency is
 * attempted independently: a resolution/install failure for one is
 * reported and skipped, never propagated as an exception, since by the
 * time this runs the primary plugin's own install has already succeeded.
 *
 * Deliberately not recursive: a just-installed dependency's own
 * `depend`/`softdepend` is never chased.
 */
object PluginDependencyChecker {

    /**
     * Checks the already-installed `source`/`id` plugin's `plugin.yml` for
     * dependencies, warns about anything missing from [toml], and (if
     * [resolveDependencies]) attempts to resolve+declare+install missing
     * hard dependencies, persisting each successful addition to
     * [tomlFile] immediately. Returns the (possibly updated) [JarletToml]
     * so callers processing multiple plugins in a loop can thread the
     * growing declared-plugins list through subsequent calls.
     *
     * A no-op (returns [toml] unchanged) if nothing was actually installed
     * for `source`/`id` (no [me.devsnox.jarlet.config.PluginStateStore] entry, or the recorded jar
     * is missing/not a readable `plugin.yml`) -- e.g. `route()` skipped an
     * unregistered source, or the fetch itself failed.
     */
    fun checkAndResolve(
        serverDir: Path,
        pluginsDir: Path,
        tomlFile: Path,
        toml: JarletToml,
        source: String,
        id: String,
        resolveDependencies: Boolean,
        trustRequested: Boolean,
    ): JarletToml {
        val installed = PluginStateStore.read(serverDir, source, id) ?: return toml
        val info = PluginYamlReader.read(pluginsDir.resolve(installed.file)) ?: return toml

        var currentToml = toml
        fun isDeclared(depName: String) = currentToml.plugins.any { it.id.equals(depName, ignoreCase = true) }

        val missingHard = info.depend.filterNot(::isDeclared)
        val missingSoft = info.softdepend.filterNot(::isDeclared)

        for (dep in missingHard) {
            val suffix = if (resolveDependencies) "" else " (pass --resolve-dependencies to install it automatically)"
            Log.info("""Warning: required dependency "$dep" of "$id" is not declared for this server; "$id" will fail to load without it$suffix""")
        }
        for (dep in missingSoft) {
            Log.info("""Note: optional dependency "$dep" of "$id" is not declared for this server; "$id" will still load, but functionality relying on "$dep" may be unavailable""")
        }

        if (!resolveDependencies) return currentToml

        for (dep in missingHard) {
            if (isDeclared(dep)) continue // may have been declared by an earlier iteration, e.g. two deps resolving to the same id

            try {
                val policy = JarletToml.Policy(track = "channel", channel = "Release")
                val declaration = PluginDeclarer.declareAndRoute(
                    serverDir, pluginsDir, tomlFile, currentToml, dep, null, policy, trustRequested,
                )
                currentToml = declaration.toml
                Log.info("""Declared "${declaration.id}" (${declaration.source}) in $tomlFile as a dependency of "$id"""")
            } catch (e: Exception) {
                Log.info("""Failed to resolve/install dependency "$dep" of "$id": ${e.message}""")
            }
        }

        return currentToml
    }
}
