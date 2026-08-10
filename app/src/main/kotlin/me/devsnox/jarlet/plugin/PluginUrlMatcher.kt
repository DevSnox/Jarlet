package me.devsnox.jarlet.plugin

import me.devsnox.jarlet.config.JarletToml

/**
 * Optional capability a [PluginSourceAdapter] may additionally implement if
 * its own URLs are ever something another adapter's external-hosting gate
 * might recognize -- Kotlin equivalent of the optional
 * `ADAPTER_URL_MATCHER` a bash adapter may set (see `src/plugin/redirect.sh`'s
 * header and `src/adapter/plugin/github-releases.sh`'s
 * `github_releases_match_url()`, the one adapter that implements this
 * today). [ExternalUrlRedirector] is the only caller.
 */
interface PluginUrlMatcher {
    /** A successful [match]: redirect to this adapter's [id]/[policy] instead. */
    data class Match(val id: String, val policy: JarletToml.Policy)

    /** Recognizes [url] as belonging to this adapter's source, or returns `null` if it doesn't. */
    fun match(url: String): Match?
}
