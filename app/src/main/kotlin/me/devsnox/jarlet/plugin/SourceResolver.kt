package me.devsnox.jarlet.plugin

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.devsnox.jarlet.Log
import me.devsnox.jarlet.config.JarletToml
import me.devsnox.jarlet.config.SysConfig
import me.devsnox.jarlet.lib.SharedHttp
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Resolves a user-supplied CLI identifier to a concrete `(source, id)`
 * pair. Per-server global id uniqueness is what makes resolving a bare
 * identifier against already-declared entries unambiguous, and is why
 * `add` takes no `<source>` positional argument.
 *
 * Unlike [PluginRouter]/[AdapterRegistry], this class has no dependency on
 * the per-source adapters at all -- it talks to Hangar's/Spiget's
 * existence-probe endpoints directly, via the shared [SharedHttp]
 * plumbing, never through an adapter.
 */
object SourceResolver {
    /** A resolved `(source, id)` pair. */
    data class Resolved(val source: String, val id: String)

    class ResolutionException(message: String) : Exception(message)

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class SpigetResource(val name: String? = null, val id: Long? = null)

    /** True if the Hangar project slug/id exists (2xx). */
    private fun probeHangarProject(slugOrId: String): Boolean {
        val api = SysConfig.default().value("HANGAR_API")
        return SharedHttp.statusOnly("$api/projects/$slugOrId") in 200..299
    }

    /** True if the Spiget resource id exists (2xx). */
    private fun probeSpigetResource(id: String): Boolean {
        val api = SysConfig.default().value("SPIGET_API")
        return SharedHttp.statusOnly("$api/resources/$id") in 200..299
    }

    /**
     * Searches Spiget by name, returning only results whose `name`
     * case-insensitively EXACTLY matches (not substring/contains).
     */
    private fun spigetExactNameMatches(name: String): List<SpigetResource> {
        val api = SysConfig.default().value("SPIGET_API")
        val encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20")

        val response = try {
            SharedHttp.get("$api/search/resources/$encoded?field=name")
        } catch (e: Exception) {
            throw ResolutionException("Spiget search request failed for '$name': ${e.message}")
        }

        if (response.status !in 200..299) {
            throw ResolutionException("Spiget search request failed for '$name' (HTTP ${response.status})")
        }

        val results = json.decodeFromString<List<SpigetResource>>(response.body)
        return results.filter { it.name?.equals(name, ignoreCase = true) == true }
    }

    /**
     * Resolves a bare `add <identifier>` (no `--source` override) to a
     * `(source, id)` pair: `owner/repo` -> github; all-digits -> probe
     * hangar then spiget; otherwise -> hangar exact slug, else spiget
     * exact-name search requiring exactly one match. Throws
     * [ResolutionException] on no-match/ambiguous-match; never guesses.
     */
    fun resolveAddIdentifier(identifier: String): Resolved {
        if (identifier.contains('/')) {
            return Resolved("github", identifier)
        }

        if (identifier.all { it.isDigit() } && identifier.isNotEmpty()) {
            val hangarOk = probeHangarProject(identifier)
            val spigetOk = probeSpigetResource(identifier)

            return when {
                hangarOk && spigetOk -> {
                    Log.warn(
                        "\"$identifier\" exists as both a Hangar project id and a Spiget resource id; " +
                            "defaulting to hangar (pass --source spiget to force the other)",
                    )
                    Resolved("hangar", identifier)
                }
                hangarOk -> Resolved("hangar", identifier)
                spigetOk -> Resolved("spiget", identifier)
                else -> throw ResolutionException("No plugin found with id '$identifier' on hangar or spiget")
            }
        }

        if (probeHangarProject(identifier)) {
            return Resolved("hangar", identifier)
        }
        Log.debug("tried hangar exact-slug match for '$identifier', not found; falling back to spiget exact-name search")

        val matches = spigetExactNameMatches(identifier)
        return when (matches.size) {
            1 -> Resolved("spiget", matches.single().id?.toString() ?: throw ResolutionException("Spiget match for '$identifier' has no id"))
            0 -> throw ResolutionException(
                "No exact match for '$identifier' on hangar or spiget. Use the numeric Spiget resource id directly, " +
                    "or check spelling, or pass --source explicitly.",
            )
            else -> {
                val listing = matches.joinToString("\n") { "  - ${it.name} (id ${it.id})" }
                throw ResolutionException("Multiple exact matches for '$identifier' on spiget; retry with --source spiget <numeric id>:\n$listing")
            }
        }
    }

    /**
     * Validates that `id` is the right shape for an explicit `--source`
     * override, mirroring each adapter's own id-shape check. Throws
     * [ResolutionException] if invalid.
     */
    fun validateSourceIdShape(source: String, id: String) {
        val ok = when (source) {
            "hangar" -> HANGAR_ID.matches(id)
            "spiget" -> SPIGET_ID.matches(id)
            "github" -> GITHUB_ID.matches(id)
            else -> throw ResolutionException("Unknown --source '$source' (expected hangar, spiget, or github)")
        }
        if (!ok) {
            val expectation = when (source) {
                "hangar" -> "a valid Hangar project slug or id"
                "spiget" -> "a numeric Spiget resource id"
                else -> "an 'owner/repo' id"
            }
            throw ResolutionException("--source $source requires $expectation, got '$id'")
        }
    }

    /**
     * Fails if `id` is already declared under ANY source (global
     * per-server id uniqueness). Throws [ResolutionException] if already
     * declared.
     */
    fun checkIdAvailable(toml: JarletToml, id: String) {
        val existing = toml.plugins.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: return
        throw ResolutionException(
            "'$id' is already declared under source '${existing.source}'; remove it first if you want to redeclare it under a different source",
        )
    }

    /**
     * Resolves a bare `remove <identifier>` / `update <identifier>`
     * against the currently declared `[[plugins]]` entries by id alone.
     * `context` is only used to name the subcommand in the "not found"
     * message. Throws [ResolutionException] if not found (or, in a state
     * that should be unreachable given [checkIdAvailable]'s invariant, if
     * declared more than once -- e.g. a hand-edited jarlet.toml).
     *
     * Also accepts a github shorthand: when [identifier] contains no `/`
     * and the exact-id match above finds nothing, it is additionally
     * tried against the repo-name portion (after the last `/`) of every
     * declared `source == "github"` entry's `owner/repo` id -- e.g.
     * `"Essentials"` matches a declared `"EssentialsX/Essentials"` --
     * since that's the only piece of a github id a user would ever
     * plausibly type from memory (the internal `owner/repo` shape exists
     * purely for hitting GitHub's API, see [GithubAdapter]'s header).
     * Full `owner/repo` ids and non-github sources are unaffected: they
     * only ever match the exact-id path above.
     */
    fun resolveDeclaredIdentifier(toml: JarletToml, identifier: String, context: String): Resolved {
        // Case-insensitive: a user typing `geyser` should match a plugin
        // declared as `Geyser` (e.g. matching Hangar's real project-slug
        // casing) -- ids are otherwise opaque strings to the user, and
        // there is no reason to make them retype the exact declared
        // casing. If two ids ever differ only by case (only possible via
        // a hand-edited jarlet.toml, since checkIdAvailable() prevents it
        // at declare time), the existing multi-match branch below still
        // reports that as the "declared under more than one source"
        // consistency error rather than silently guessing.
        var matches = toml.plugins.filter { it.id.equals(identifier, ignoreCase = true) }

        // Exact-id match found nothing: fall back to the github
        // repo-name shorthand, but only for bare (no `/`) identifiers --
        // a full "owner/repo" typo should never silently match some
        // other repo by name alone.
        if (matches.isEmpty() && !identifier.contains('/')) {
            matches = toml.plugins.filter {
                it.source == "github" && it.id.substringAfterLast('/').equals(identifier, ignoreCase = true)
            }
        }

        if (matches.isEmpty()) {
            throw ResolutionException("No declared plugin with id '$identifier' (for $context)")
        }
        if (matches.size > 1) {
            throw ResolutionException(
                "Internal consistency error: id '$identifier' is declared under more than one source in jarlet.toml; fix this by hand before retrying",
            )
        }

        // Return the declared entry's own canonical-cased id (not the
        // user's raw, possibly differently-cased, input) so downstream
        // exact-match lookups (e.g. RemoveCommand's toml rewrite,
        // PluginStateStore reads/writes) keep matching correctly.
        return Resolved(matches.single().source, matches.single().id)
    }

    private val HANGAR_ID = Regex("^[0-9A-Za-z._-]+$")
    private val SPIGET_ID = Regex("^[0-9]+$")
    private val GITHUB_ID = Regex("^[0-9A-Za-z._-]+/[0-9A-Za-z._-]+$")
}
