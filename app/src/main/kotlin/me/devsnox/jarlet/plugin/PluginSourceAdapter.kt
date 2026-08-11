package me.devsnox.jarlet.plugin

import java.nio.file.Path
import me.devsnox.jarlet.config.JarletToml

/**
 * Shared contract every plugin source (`hangar`, `spiget`, `github`)
 * implements as a Kotlin `object`, registered in a static, reflection-free
 * registry ([AdapterRegistry]).
 *
 * [sourceName] is the internal source string (matches `jarlet.toml`'s
 * `source` field and `plugins-state.json`'s `source` field). Since
 * registration is a hand-written `mapOf(...)` (see [AdapterRegistry]),
 * the map key *is* the source of truth for validity.
 *
 * [displayName] is the purely cosmetic, human-readable name
 * (e.g. `"Hangar"`, `"SpigotMC"`) [AdapterRegistry.displayName] falls back
 * to [sourceName] for. It carries zero routing/storage meaning.
 *
 * [process] is the entry point [PluginRouter] calls for a declared entry.
 * `source` itself isn't a parameter since it's already implied by which
 * adapter got looked up. Adapters are expected to be fully side-effecting
 * (fetch, verify, write the jar under `plugins_dir`, and persist the
 * result via [me.devsnox.jarlet.config.PluginStateStore.write]), so there
 * is no return value to thread back through the router.
 *
 * Deliberately *not* `suspend`: routing stays fully sequential,
 * one-plugin-at-a-time, until parallel fetches become a real feature
 * request -- no coroutines dependency is declared in
 * `app/build.gradle.kts`.
 */
interface PluginSourceAdapter {
    /** The internal source string this adapter serves, e.g. `"hangar"`. */
    val sourceName: String

    /** Cosmetic-only display name; defaults to [sourceName] if an adapter has nothing nicer to show. */
    val displayName: String get() = sourceName

    /**
     * Fetches/updates the declared plugin identified by [id] under
     * [policy], installs it into [pluginsDir], and records the result via
     * [me.devsnox.jarlet.config.PluginStateStore.write]. [trustRequested] is threaded straight
     * through from the CLI's `--trust` flag, for adapters that need it to
     * pass on to an external-hosting gate.
     */
    fun process(
        serverDir: Path,
        pluginsDir: Path,
        id: String,
        policy: JarletToml.Policy,
        trustRequested: Boolean,
    )
}
