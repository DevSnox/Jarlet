package me.devsnox.jarlet.config

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/**
 * Global, per-domain trust list for externally-hosted plugin downloads --
 * Kotlin port of the trust-list half of `src/plugin/trust.sh`
 * (`trusted_sources_file()`, `url_domain()`, `is_domain_trusted()`,
 * `trust_domain()`). See [me.devsnox.jarlet.plugin.UntrustedExternalDownloader] for the other half
 * (`handle_untrusted_external_url()`, the actual fetch-and-verify gate).
 *
 * What this solves: [me.devsnox.jarlet.plugin.ExternalUrlRedirector] only handles an external URL
 * that belongs to a source Jarlet already has a working adapter for.
 * Plenty of external hosts (e.g. Geyser's own `download.geysermc.org`) are
 * neither GitHub, Spiget, nor Hangar -- for those, an adapter's
 * external-hosting gate falls through to this store as a second, different
 * kind of fallback: not "recognize this URL as another adapter", but "has
 * the user explicitly said they trust this domain enough to fetch a plain,
 * unverified-by-any-adapter file from it".
 *
 * Trust is domain-level (not exact-URL, not prefix) and persisted globally
 * (not per-server), same reasoning as the bash version: the concrete
 * motivating case (Geyser's `.../versions/latest/builds/latest/downloads/spigot`)
 * is itself a versionless "latest" API path, not a specific file -- pinning
 * trust to that exact string would be trust in name only, since the actual
 * bytes behind it change over time regardless.
 *
 * Storage location judgment call: bash persists this at
 * `$SCRIPT_DIR/../$(sys_config_value TRUSTED_SOURCES_FILENAME)`, i.e.
 * next to the installed `jarlet` script and its `jarlet-sys.conf`. A
 * compiled JVM binary has no equivalent "next to the install" directory
 * (`jarlet-sys.conf` is bundled as a classpath resource here, not read
 * from a sibling file -- see [SysConfig]), so this instead uses a
 * `JARLET_HOME` env var override, falling back to `~/jarlet` -- the same
 * root [me.devsnox.jarlet.server.ServerPaths.serversRoot]'s default
 * (`~/jarlet/servers`) sits under, keeping every Jarlet-managed file
 * under one predictable home directory absent an explicit override. Not
 * verified against any other phase's choice (none existed yet at the time
 * this was written) -- flagged for reconciliation if a `JARLET_HOME`
 * concept is introduced elsewhere later.
 */
object TrustedSourceStore {
    /** The global trust-list file (not guaranteed to exist yet -- [isTrusted] handles that; [trust] is the only writer and creates it on first use). */
    fun file(): Path {
        val home = System.getenv("JARLET_HOME")?.takeIf { it.isNotEmpty() }
            ?: Paths.get(System.getProperty("user.home"), "jarlet").toString()
        val filename = SysConfig.default().value("TRUSTED_SOURCES_FILENAME")
        return Paths.get(home).resolve(filename)
    }

    /** Extracts the host (domain, port stripped) from [url], e.g. `"https://download.geysermc.org/v2/..."` -> `"download.geysermc.org"`. Mirrors `url_domain()`. */
    fun domainOf(url: String): String =
        try {
            URI(url).host ?: url
        } catch (e: Exception) {
            url
        }

    /** True if [domain] is present as its own line in the trust file (blank lines and `#`-comments ignored, exact match only -- no wildcarding). Mirrors `is_domain_trusted()`. */
    fun isTrusted(domain: String): Boolean {
        val path = file()
        if (!Files.isRegularFile(path)) return false

        return Files.readAllLines(path).any { rawLine ->
            val line = rawLine.trim()
            line.isNotEmpty() && !line.startsWith("#") && line == domain
        }
    }

    /** Appends [domain] to the trust file if not already present, creating the file (with a short header comment) on first use. Mirrors `trust_domain()`. */
    fun trust(domain: String) {
        if (isTrusted(domain)) return

        val path = file()
        if (!Files.isRegularFile(path)) {
            Files.createDirectories(path.parent)
            Files.writeString(
                path,
                """
                # jarlet-trusted-sources.conf
                # Domains Jarlet will download plugin jars from directly (plain HTTP, no
                # adapter, best-effort verification only) when an adapter reports a plugin
                # as hosted externally at a URL on one of these domains. One domain per
                # line. Populated by `plugin <name> add|update ... --trust`; hand-edit is
                # also fine, this is just a flat allowlist.
                """.trimIndent() + "\n",
            )
        }

        Files.writeString(path, "$domain\n", StandardOpenOption.APPEND)
    }
}
