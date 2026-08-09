package me.devsnox.jarlet.plugin

import me.devsnox.jarlet.adapter.plugin.GithubReleasesAdapter
import me.devsnox.jarlet.adapter.plugin.HangarAdapter
import me.devsnox.jarlet.adapter.plugin.SpigetAdapter

/**
 * Static, reflection-free replacement for `src/plugin/router.sh`'s
 * `load_adapter()` (lazy-sourcing `../adapter/plugin/<source>.sh` and
 * reading back its self-declared `ADAPTER_SOURCE_NAME`/
 * `ADAPTER_ENTRY_FUNCTION`/`ADAPTER_DISPLAY_NAME` via bash's
 * indirect-variable-expansion trick). Per the migration plan's
 * architecture decision #2: one `object` per source implementing
 * [PluginSourceAdapter], registered here in a hand-written map --
 * GraalVM-native-image-friendly (no reflection config needed) while
 * keeping the bash contract's spirit (one adapter per source, sanity-
 * checked name -- here, simply "the map key").
 *
 * All three phase 4 adapters (`hangar`, `github-releases`, `spiget`, same
 * order they were built in bash) are now registered below.
 */
object AdapterRegistry {
    private val adaptersBySource: Map<String, PluginSourceAdapter> = listOf(
        HangarAdapter,
        GithubReleasesAdapter,
        SpigetAdapter,
    ).associateBy { it.sourceName }

    /** The adapter registered for [source], or `null` if none is implemented yet. */
    fun find(source: String): PluginSourceAdapter? = adaptersBySource[source]

    /**
     * Every registered adapter, in no particular order. Exists for callers
     * that need to look across all adapters rather than by a single known
     * `source` -- e.g. [ExternalUrlRedirector], which per its own header
     * note wants to derive its `PluginUrlMatcher`-implementing adapter list
     * from this registry (`AdapterRegistry.all().filterIsInstance<PluginUrlMatcher>()`)
     * once one exists, instead of a hand-written list, so a newly-registered
     * adapter's matcher (if any) is picked up automatically.
     */
    fun all(): Collection<PluginSourceAdapter> = adaptersBySource.values

    /**
     * Cosmetic-only accessor used by [me.devsnox.jarlet.command.ListCommand]
     * to print a human-readable name instead of the raw internal source
     * string. Kotlin equivalent of `router.sh`'s `adapter_display_name()`
     * -- falls back to the raw [source] string itself if no adapter is
     * registered for it (e.g. a stale/hand-edited `jarlet.toml` entry, or
     * simply a source not implemented yet in this phase), exactly like the
     * bash version's fallback for "no adapter file exists".
     */
    fun displayName(source: String): String = adaptersBySource[source]?.displayName ?: source
}
