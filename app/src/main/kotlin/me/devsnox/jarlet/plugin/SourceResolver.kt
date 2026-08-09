package me.devsnox.jarlet.plugin

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.devsnox.jarlet.Log
import me.devsnox.jarlet.config.JarletToml
import me.devsnox.jarlet.config.SysConfig
import me.devsnox.jarlet.http.SharedHttp
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Kotlin port of `src/plugin/resolve.sh` -- resolves a user-supplied CLI
 * identifier to a concrete `(source, id)` pair. See that file's header for
 * the full rationale (per-server global id uniqueness is what makes
 * resolving a bare identifier against already-declared entries
 * unambiguous, and is why `add` takes no `<source>` positional argument at
 * all any more).
 *
 * Unlike [PluginRouter]/[AdapterRegistry] (see those files' doc comments
 * for their integration status), this class has no dependency on the
 * per-source adapters at all -- same as `resolve.sh` itself, it only talks
 * to Hangar's/Spiget's existence-probe endpoints directly, via the shared
 * [SharedHttp] plumbing (also landed as part of the phase 3/4 work
 * happening in parallel with this one), never through an adapter -- so it
 * is implemented here in full rather than against an assumed interface,
 * even though the module mapping table in the migration plan groups it
 * with phase 4 ("Plugin adapters"). It was ported as part of this phase,
 * since [AddCommand] hard-depends on it and neither phase 3 nor phase 4
 * had produced it yet when this phase started (see this port's final
 * report for the full status writeup).
 */
object SourceResolver {
    /** A resolved `(source, id)` pair -- Kotlin equivalent of resolve.sh's `RESOLVED_SOURCE`/`RESOLVED_ID` "out parameters". */
    data class Resolved(val source: String, val id: String)

    class ResolutionException(message: String) : Exception(message)

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class SpigetResource(val name: String? = null, val id: Long? = null)

    /** True if the Hangar project slug/id exists (2xx). Kotlin equivalent of `resolve_probe_hangar_project()`. */
    private fun probeHangarProject(slugOrId: String): Boolean {
        val api = SysConfig.default().value("HANGAR_API")
        return SharedHttp.statusOnly("$api/projects/$slugOrId") in 200..299
    }

    /** True if the Spiget resource id exists (2xx). Kotlin equivalent of `resolve_probe_spiget_resource()`. */
    private fun probeSpigetResource(id: String): Boolean {
        val api = SysConfig.default().value("SPIGET_API")
        return SharedHttp.statusOnly("$api/resources/$id") in 200..299
    }

    /**
     * Searches Spiget by name, returning only results whose `name`
     * case-insensitively EXACTLY matches (not substring/contains). Kotlin
     * equivalent of `resolve_spiget_exact_name_matches()`.
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
     * `(source, id)` pair. Kotlin equivalent of `resolve_add_identifier()`
     * -- see that function's doc comment for the exact algorithm
     * (`owner/repo` -> github; all-digits -> probe hangar then
     * spiget; otherwise -> hangar exact slug, else spiget exact-name
     * search requiring exactly one match). Throws [ResolutionException] on
     * no-match/ambiguous-match; never guesses.
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
                    // "Warning: " stripped from the literal here -- Log.warn()
                    // prepends its own, so keeping both would double it up.
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
     * override, mirroring each adapter's own id-shape check. Kotlin
     * equivalent of `resolve_validate_source_id_shape()`. Throws
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
     * per-server id uniqueness). Kotlin equivalent of
     * `resolve_check_id_available()`. Throws [ResolutionException] if
     * already declared.
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
     * Kotlin equivalent of `resolve_declared_identifier()`. `context` is
     * only used to name the subcommand in the "not found" message. Throws
     * [ResolutionException] if not found (or, in a state that should be
     * unreachable given [checkIdAvailable]'s invariant, if declared more
     * than once -- e.g. a hand-edited jarlet.toml).
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
        val matches = toml.plugins.filter { it.id.equals(identifier, ignoreCase = true) }

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
