package me.devsnox.jarlet.plugin

import java.nio.file.Path
import me.devsnox.jarlet.config.JarletToml

/**
 * Shared contract every plugin source (`hangar`, `spiget`,
 * `github-releases` -- all phase 4, not implemented yet) will implement as
 * a Kotlin `object`, per the migration plan's architecture decision #2:
 * a static, reflection-free registry ([AdapterRegistry]) instead of bash's
 * self-describing sourced-file dynamic dispatch
 * (`ADAPTER_SOURCE_NAME`/`ADAPTER_ENTRY_FUNCTION` + indirect expansion in
 * `src/plugin/router.sh`'s `load_adapter()`).
 *
 * [sourceName] is the internal source string (matches `jarlet.toml`'s
 * `source` field and `plugins-state.json`'s `source` field) -- equivalent
 * to `ADAPTER_SOURCE_NAME`. Since registration is a hand-written
 * `mapOf(...)` (see [AdapterRegistry]) rather than sourcing a file whose
 * name must match, there's no dynamic sanity check to reproduce here; the
 * map key *is* the source of truth.
 *
 * [displayName] is the purely cosmetic, human-readable name
 * (e.g. `"Hangar"`, `"SpigotMC"`) [AdapterRegistry.displayName] falls back
 * to [sourceName] for -- equivalent to the optional `ADAPTER_DISPLAY_NAME`.
 * It carries zero routing/storage meaning, exactly like the bash version.
 *
 * [process] is the entry point [PluginRouter] calls for a declared entry,
 * with the same parameter shape `route_plugin()` passes to
 * `ADAPTER_ENTRY_FUNCTION` (`server_dir`, `plugins_dir`, `id`,
 * `policy_json`, `trust_requested`) -- `source` itself is dropped since
 * it's already implied by which adapter got looked up. Adapters are
 * expected to be fully side-effecting (fetch, verify, write the jar under
 * `plugins_dir`, and persist the result via [PluginStateStore.write]) the
 * same way the bash adapters are, so there is no return value to thread
 * back through the router.
 *
 * Deliberately *not* `suspend`: the plan's recommended-defaults section
 * calls for staying fully sequential (matching `router.sh`'s one-plugin-
 * at-a-time loops) until parallel fetches become a real feature request,
 * and no coroutines dependency is declared in `app/build.gradle.kts` yet.
 *
 * Deliberately does *not* yet include an equivalent of the optional
 * `ADAPTER_URL_MATCHER`/`ADAPTER_DISPLAY_NAME`-for-redirects mechanism
 * `redirect.sh` relies on, or the `--trust` external-hosting gate's own
 * shape beyond threading [Boolean] through -- both belong to phase 4
 * (`ExternalUrlRedirector`, `TrustedSourceStore`/`UntrustedExternalDownloader`)
 * alongside the adapter that first needs them, per the porting sequence.
 * Adding them here now would mean guessing at a shape phase 4 hasn't
 * earned yet.
 */
interface PluginSourceAdapter {
    /** The internal source string this adapter serves, e.g. `"hangar"`. */
    val sourceName: String

    /** Cosmetic-only display name; defaults to [sourceName] if an adapter has nothing nicer to show. */
    val displayName: String get() = sourceName

    /**
     * Fetches/updates the declared plugin identified by [id] under
     * [policy], installs it into [pluginsDir], and records the result via
     * [PluginStateStore.write]. [trustRequested] is threaded straight
     * through from the CLI's `--trust` flag (phase 5), for adapters that
     * need it to pass on to an external-hosting gate (phase 4).
     */
    fun process(
        serverDir: Path,
        pluginsDir: Path,
        id: String,
        policy: JarletToml.Plugin.Policy,
        trustRequested: Boolean,
    )
}
