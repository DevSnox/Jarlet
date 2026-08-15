package me.devsnox.jarlet.lib

/**
 * Minimal semantic-version value used by the minor/patch auto-update
 * policy -- shared across the plugin source adapters
 * ([me.devsnox.jarlet.adapter.plugin.GithubAdapter],
 * [me.devsnox.jarlet.adapter.plugin.SpigetAdapter], [me.devsnox.jarlet.adapter.plugin.HangarAdapter])
 * and [me.devsnox.jarlet.adapter.server.PaperMcAdapter], same as [SharedHttp]
 * is shared HTTP plumbing for those same adapters.
 *
 * Only `X.Y.Z` is modeled -- no pre-release/build-metadata comparison --
 * since every source's version string here is either already exactly that
 * shape (Minecraft versions), or is being tolerantly parsed down to that
 * shape by dropping a leading `v` and any trailing suffix (see [parse]).
 */
data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
    override fun compareTo(other: SemVer) = compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch)

    /** True if [other] is within [track]'s bound relative to this baseline: "minor" = same major; "patch" = same major.minor. */
    fun bounds(other: SemVer, track: String): Boolean = when (track) {
        "minor" -> other.major == major
        "patch" -> other.major == major && other.minor == minor
        else -> false
    }

    companion object {
        private val PATTERN = Regex("""^v?(\d+)\.(\d+)\.(\d+)""")

        /** Parses the leading `[v]X.Y.Z` out of [raw] (ignoring any trailing build metadata/suffix), or null if it isn't semver-shaped. */
        fun parse(raw: String): SemVer? {
            val match = PATTERN.find(raw) ?: return null
            val (major, minor, patch) = match.destructured
            return SemVer(major.toInt(), minor.toInt(), patch.toInt())
        }
    }
}

/**
 * Picks the highest [T] whose version (via [versionOf]) parses as semver and
 * is within [track]'s bound of [baseline] -- or null if none qualify (e.g.
 * every candidate is unparseable, or none falls within the bound). Callers
 * are responsible for logging a skip message per excluded candidate if
 * that's useful in context; this function is silent.
 */
fun <T> pickHighestWithinBound(candidates: List<T>, versionOf: (T) -> String, baseline: SemVer, track: String): T? =
    candidates
        .mapNotNull { c -> SemVer.parse(versionOf(c))?.let { c to it } }
        .filter { (_, v) -> baseline.bounds(v, track) }
        .maxByOrNull { (_, v) -> v }
        ?.first
