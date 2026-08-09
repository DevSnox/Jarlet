package me.devsnox.jarlet.plugin

import me.devsnox.jarlet.config.JarletToml
import java.nio.file.Path

/**
 * Resolves a source's "hosted externally, install manually" gate to another
 * source adapter Jarlet already has, when the external URL is recognizable
 * as belonging to it -- e.g. Spiget's EssentialsX resource (id 9089)
 * reports `file.externalUrl:
 * https://github.com/EssentialsX/Essentials/releases/tag/2.22.0` (confirmed
 * live against api.spiget.org), a URL
 * [me.devsnox.jarlet.adapter.plugin.GithubReleasesAdapter] can actually
 * fetch from instead of the plugin just being skipped. Kotlin port of
 * `src/plugin/redirect.sh`.
 *
 * Discovery in the bash version is generic, not hardcoded to
 * github-releases: every adapter file is tried in turn, and only those that
 * set the optional `ADAPTER_URL_MATCHER` are asked whether they recognize
 * the URL. The equivalent generic mechanism here is [PluginUrlMatcher] --
 * any [PluginSourceAdapter] may additionally implement it. [matchers] is
 * derived from [AdapterRegistry] (every registered adapter that also
 * implements [PluginUrlMatcher]) rather than a hand-written list, so a
 * newly-registered adapter's matcher (if any -- only
 * [me.devsnox.jarlet.adapter.plugin.GithubReleasesAdapter]'s today) is
 * picked up automatically with no change needed here.
 */
object ExternalUrlRedirector {
    private val matchers: List<PluginSourceAdapter> by lazy {
        AdapterRegistry.all().filter { it is PluginUrlMatcher }
    }

    /** A successful [tryResolve]: redirect to [source]/[id] under [policy] instead. */
    data class Redirect(val source: String, val id: String, val policy: JarletToml.Plugin.Policy)

    /**
     * Tries every adapter that implements [PluginUrlMatcher] to see if it
     * recognizes [url] as its own. Returns the first match, or `null` if
     * [url] is null/empty or no known adapter's matcher recognizes it.
     * Mirrors `try_resolve_external_url()`.
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
     * [PluginSourceAdapter.process] -- the same recursive dispatch
     * `route_plugin()` performs right after a successful redirect (see
     * `hangar.sh`'s/`spiget.sh`'s external-hosting gates, which call
     * `persist_external_redirect()` then `route_plugin()` back-to-back).
     *
     * NOTE (incompleteness, flagged for reconciliation): unlike the bash
     * version's `persist_external_redirect()`, this does NOT rewrite
     * `jarlet.toml` to record the redirect -- that requires the
     * TOML-rewrite machinery ([JarletToml.write]'s full-rewrite path,
     * commented on there) that add/remove/update (phase 5, not yet ported)
     * owns, per [PluginStateStore]'s own header note that `jarlet.toml`
     * rewriting is out of scope for the phase-4 plugin subsystem. The
     * practical effect: a redirected plugin is still correctly fetched via
     * its real source every run, but the redirect is re-resolved (one extra
     * HTTP round-trip) on every future run instead of being persisted after
     * the first. Once phase 5 exists, this should call an equivalent of
     * `persist_external_redirect()` before dispatching.
     */
    fun dispatch(redirect: Redirect, serverDir: Path, pluginsDir: Path, trustRequested: Boolean) {
        val adapter = matchers.first { it.sourceName == redirect.source }
        adapter.process(serverDir, pluginsDir, redirect.id, redirect.policy, trustRequested)
    }
}
