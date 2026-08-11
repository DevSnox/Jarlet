package me.devsnox.jarlet.plugin

import me.devsnox.jarlet.adapter.plugin.GithubAdapter
import me.devsnox.jarlet.adapter.plugin.HangarAdapter
import me.devsnox.jarlet.adapter.plugin.SpigetAdapter

/**
 * Static, reflection-free registry resolving a plugin `source` string to
 * its adapter. One `object` per source implements [PluginSourceAdapter],
 * registered here in a hand-written map -- GraalVM-native-image-friendly,
 * since no reflection config is needed.
 *
 * The three registered adapters are `hangar`, `github`, and `spiget`.
 */
object AdapterRegistry {
    private val adaptersBySource: Map<String, PluginSourceAdapter> = listOf(
        HangarAdapter,
        GithubAdapter,
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
     * string -- falls back to the raw [source] string itself if no adapter
     * is registered for it (e.g. a stale/hand-edited `jarlet.toml` entry,
     * or a source with no adapter implemented yet).
     */
    fun displayName(source: String): String = adaptersBySource[source]?.displayName ?: source
}
