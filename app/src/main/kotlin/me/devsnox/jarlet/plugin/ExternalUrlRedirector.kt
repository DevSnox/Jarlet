package me.devsnox.jarlet.plugin

import me.devsnox.jarlet.config.JarletToml
import java.nio.file.Path

/**
 * Resolves a source's "hosted externally, install manually" gate to another
 * source adapter Jarlet already has, when the external URL is recognizable
 * as belonging to it -- e.g. Spiget's EssentialsX resource (id 9089)
 * reports `file.externalUrl:
 * https://github.com/EssentialsX/Essentials/releases/tag/2.22.0`, a URL
 * [me.devsnox.jarlet.adapter.plugin.GithubAdapter] can actually
 * fetch from instead of the plugin just being skipped.
 *
 * Discovery is generic, not hardcoded to github: [PluginUrlMatcher] is the
 * mechanism any [PluginSourceAdapter] may additionally implement to
 * recognize its own URLs. [matchers] is derived from [AdapterRegistry]
 * (every registered adapter that also implements [PluginUrlMatcher])
 * rather than a hand-written list, so a newly-registered adapter's matcher
 * (if any -- only [me.devsnox.jarlet.adapter.plugin.GithubAdapter]'s
 * today) is picked up automatically with no change needed here.
 */
object ExternalUrlRedirector {
    private val matchers: List<PluginSourceAdapter> by lazy {
        AdapterRegistry.all().filter { it is PluginUrlMatcher }
    }

    /** A successful [tryResolve]: redirect to [source]/[id] under [policy] instead. */
    data class Redirect(val source: String, val id: String, val policy: JarletToml.Policy)

    /**
     * Tries every adapter that implements [PluginUrlMatcher] to see if it
     * recognizes [url] as its own. Returns the first match, or `null` if
     * [url] is null/empty or no known adapter's matcher recognizes it.
     */
    fun tryResolve(url: String?): Redirect? {
        if (url.isNullOrEmpty()) return null

        for (adapter in matchers) {
            val matcher = adapter as? PluginUrlMatcher ?: continue
            val match = matcher.match(url) ?: continue
            return Redirect(adapter.sourceName, match.id, match.policy)
        }

        return null
    }

    /**
     * Routes an already-resolved [redirect] to its target adapter's
     * [PluginSourceAdapter.process].
     *
     * NOTE: this does NOT rewrite `jarlet.toml` to record the redirect --
     * that requires the TOML-rewrite machinery ([JarletToml.write]'s
     * full-rewrite path) owned by the add/remove/update commands, which is
     * out of scope here (see [me.devsnox.jarlet.config.PluginStateStore]'s
     * own note that `jarlet.toml` rewriting isn't this subsystem's job).
     * The practical effect: a redirected plugin is still correctly fetched
     * via its real source every run, but the redirect is re-resolved (one
     * extra HTTP round-trip) on every future run instead of being
     * persisted after the first.
     */
    fun dispatch(redirect: Redirect, serverDir: Path, pluginsDir: Path, trustRequested: Boolean) {
        val adapter = matchers.first { it.sourceName == redirect.source }
        adapter.process(serverDir, pluginsDir, redirect.id, redirect.policy, trustRequested)
    }
}
