package me.devsnox.jarlet.adapter.plugin

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import me.devsnox.jarlet.Log
import me.devsnox.jarlet.config.JarletToml
import me.devsnox.jarlet.config.SysConfig
import me.devsnox.jarlet.plugin.ExternalUrlRedirector
import me.devsnox.jarlet.config.InstalledVersion
import me.devsnox.jarlet.lib.SemVer
import me.devsnox.jarlet.lib.SharedHttp
import me.devsnox.jarlet.lib.pickHighestWithinBound
import me.devsnox.jarlet.plugin.PluginSourceAdapter
import me.devsnox.jarlet.config.PluginStateStore
import me.devsnox.jarlet.plugin.UntrustedExternalDownloader
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.math.abs

/** Thrown for the same failure cases `fail()` covers throughout `src/adapter/plugin/spiget.sh`. */
class SpigetAdapterException(message: String) : Exception(message)

/**
 * Spiget (SpigotMC) plugin source adapter -- Kotlin port of
 * `src/adapter/plugin/spiget.sh`. Spiget (`https://api.spiget.org/v2`)
 * needs no API key/auth (confirmed live), unlike [HangarAdapter] -- so
 * unlike that adapter there is no authenticate/JWT machinery here at all.
 *
 * Two real gaps versus [HangarAdapter]/[me.devsnox.jarlet.adapter.server.PaperAdapter],
 * both confirmed by live probes against the real API (not just the
 * research doc) before the bash version was written -- preserved here:
 *
 *   1. No checksum of any kind is exposed anywhere in Spiget's API.
 *      Verification is size-only, and even that is best-effort: only the
 *      *resource*-level `file.size` is ever populated (in KB/MB/GB, not
 *      bytes -- see [sizeToBytes]), and it isn't guaranteed to match any
 *      specific version's file -- it's also simply 0/unset for many
 *      resources (e.g. externally-hosted ones). A warning is always
 *      printed that no cryptographic verification was possible.
 *   2. A version's `uuid` (the authoritative, persisted identity) is NOT
 *      directly queryable in `/versions/{version}` -- only the numeric
 *      `id` (or the literal `"latest"`) resolves there; a uuid in that
 *      position 404s (confirmed live, and per the swagger spec itself,
 *      which types `version` as "Version ID or 'latest'"). Resolving a
 *      pinned uuid back to a numeric id therefore requires a bounded scan
 *      of the versions list -- see [resolvePinnedVersion].
 *
 * Also confirmed live: the plain (non-proxy) `/download` endpoint
 * redirects to a spigotmc.org HTML resource page, not a raw file
 * (SpigotMC requires a browser click-through) -- so this adapter always
 * uses `.../download/proxy`, which does return the raw file directly. The
 * proxy endpoint's documented "pretty strict rate-limit" is why this
 * adapter, like [HangarAdapter], only ever downloads once a
 * version-uuid mismatch has already been established by a cheap
 * metadata-only check.
 */
object SpigetAdapter : PluginSourceAdapter {
    override val sourceName: String = "spiget"
    override val displayName: String = "SpigotMC"

    private val VALID_ID = Regex("^[0-9]+$")
    private val NUMERIC = Regex("^[0-9]+$")
    private val DASH_RUN = Regex("-+")

    private val json = Json { ignoreUnknownKeys = true }

    private val spigetApi: String by lazy { SysConfig.default().value("SPIGET_API") }
    private val versionListPageSize: Int by lazy { SysConfig.default().value("SPIGET_VERSION_LIST_PAGE_SIZE").toInt() }
    private val versionListMaxPages: Int by lazy { SysConfig.default().value("SPIGET_VERSION_LIST_MAX_PAGES").toInt() }

    /** Plain GET against `$SPIGET_API$path`. No auth header of any kind -- confirmed live that Spiget's API is fully anonymous. Mirrors `spiget_get()`. */
    private fun get(path: String): String {
        val response = try {
            SharedHttp.get("$spigetApi$path")
        } catch (e: IOException) {
            throw SpigetAdapterException("Spiget request failed: $path")
        }
        if (response.status !in 200..299) {
            throw SpigetAdapterException("Spiget request to $path failed with HTTP ${response.status}")
        }
        return response.body
    }

    /**
     * Resolves a pinned [pin] (version uuid) to its full version metadata
     * by scanning the versions list newest-first, bounded to 5 pages of
     * 100 (500 most recent versions) -- if a pin is older than that, this
     * throws with a clear message rather than scanning indefinitely
     * against a rate-limited API. Mirrors `spiget_resolve_pinned_version()`.
     */
    private fun resolvePinnedVersion(id: String, pin: String): SpigetVersionResponse {
        for (page in 1..5) {
            val pageJson = try {
                get("/resources/$id/versions?size=100&page=$page&sort=-releaseDate")
            } catch (e: SpigetAdapterException) {
                throw SpigetAdapterException("Could not list versions for '$id' while resolving pin '$pin'")
            }

            val versions = try {
                json.decodeFromString(ListSerializer(SpigetVersionResponse.serializer()), pageJson)
            } catch (e: Exception) {
                throw SpigetAdapterException("Could not list versions for '$id' while resolving pin '$pin'")
            }

            val match = versions.firstOrNull { it.uuid == pin }
            if (match != null) return match

            // Fewer than a full page means we've reached the end of the list.
            if (versions.size < 100) break
        }

        throw SpigetAdapterException(
            "Could not find pinned Spiget version '$pin' for resource '$id' (searched up to 500 most recent versions)",
        )
    }

    /**
     * Pages through `GET /resources/$id/versions` newest-first, bounded by
     * `SPIGET_VERSION_LIST_MAX_PAGES` pages of
     * `SPIGET_VERSION_LIST_PAGE_SIZE` each (per `jarlet-sys.conf`), and
     * picks the highest version within [track]'s bound of [baseline] via
     * [pickHighestWithinBound] (using each version's human-readable `name`
     * field). Returns null if none qualify. Mirrors
     * [resolvePinnedVersion]'s bounded-page-scan structure exactly, but
     * reads its page size/page count from config instead of that
     * function's own hardcoded `100`/`5` -- [resolvePinnedVersion] itself
     * is untouched.
     */
    private fun fetchVersionWithinBound(id: String, baseline: SemVer, track: String): SpigetVersionResponse? {
        val candidates = mutableListOf<SpigetVersionResponse>()

        for (page in 1..versionListMaxPages) {
            val pageJson = try {
                get("/resources/$id/versions?size=$versionListPageSize&page=$page&sort=-releaseDate")
            } catch (e: SpigetAdapterException) {
                throw SpigetAdapterException("Could not list versions for '$id' while resolving track=$track bound")
            }

            val versions = try {
                json.decodeFromString(ListSerializer(SpigetVersionResponse.serializer()), pageJson)
            } catch (e: Exception) {
                throw SpigetAdapterException("Could not list versions for '$id' while resolving track=$track bound")
            }

            candidates += versions

            // Fewer than a full page means we've reached the end of the list.
            if (versions.size < versionListPageSize) break
        }

        return pickHighestWithinBound(candidates, { it.name ?: "" }, baseline, track)
    }

    /**
     * Converts a Spiget resource-level `file.size` (a float in
     * [unit]) to an approximate byte count -- best-effort only (see the
     * class doc's "no checksum" gap). Assumes 1024-based units
     * (KB/MB/GB), the common convention; there is no authoritative spec
     * for which base Spiget itself uses. Returns `null` for an
     * unrecognized unit. Mirrors `spiget_size_to_bytes()`.
     */
    private fun sizeToBytes(size: Double, unit: String?): Long? {
        val multiplier = when (unit?.uppercase()) {
            "", null, "B" -> 1L
            "KB" -> 1024L
            "MB" -> 1024L * 1024L
            "GB" -> 1024L * 1024L * 1024L
            else -> return null
        }
        return Math.round(size * multiplier)
    }

    /**
     * Sanitizes an arbitrary Spiget resource name into a safe jar filename
     * component (mirrors [HangarAdapter]'s `"$slug.jar"` fallback, but
     * spiget's id is purely numeric and not human-readable, so the
     * resource name is preferred when available). Mirrors
     * `spiget_sanitize_filename()`.
     */
    private fun sanitizeFilename(name: String): String {
        val replaced = name.map { c -> if (c.isLetterOrDigit() || c in "._-") c else '-' }.joinToString("").lowercase()
        return replaced.replace(DASH_RUN, "-").trim('-')
    }

    override fun process(
        serverDir: Path,
        pluginsDir: Path,
        id: String,
        policy: JarletToml.Policy,
        trustRequested: Boolean,
    ) {
        if (!VALID_ID.matches(id)) {
            throw SpigetAdapterException("Invalid Spiget resource id: $id (must be numeric)")
        }

        val resource = try {
            json.decodeFromString(SpigetResourceResponse.serializer(), get("/resources/$id"))
        } catch (e: SpigetAdapterException) {
            throw e
        } catch (e: Exception) {
            throw SpigetAdapterException("Could not fetch Spiget resource '$id'")
        }

        val name = resource.name
        val label = name?.takeIf { it.isNotEmpty() } ?: id

        if (resource.external == true) {
            val externalUrl = resource.file?.externalUrl

            if (!externalUrl.isNullOrEmpty()) {
                val redirect = ExternalUrlRedirector.tryResolve(externalUrl)
                if (redirect != null) {
                    Log.info(
                        "\"$label\" ($id) is hosted externally at $externalUrl -- redirecting to ${redirect.id} (${redirect.source})",
                    )
                    ExternalUrlRedirector.dispatch(redirect, serverDir, pluginsDir, trustRequested)
                    return
                }
            }

            if (externalUrl.isNullOrEmpty()) {
                Log.info("Skipping \"$label\" ($id): resource is hosted externally, install manually (no external URL was reported by Spiget)")
                return
            }

            UntrustedExternalDownloader.handle(
                serverDir = serverDir,
                pluginsDir = pluginsDir,
                source = sourceName,
                id = id,
                label = label,
                externalUrl = externalUrl,
                expectedHash = null,
                expectedSize = null,
                fallbackFilename = "spiget-$id.jar",
                trustRequested = trustRequested,
            )
            return
        }

        if (resource.premium == true) {
            Log.info("Skipping \"$label\" ($id): resource is premium/paid, install manually")
            return
        }

        val pin = policy.pin

        // Same gating as GithubAdapter: only pin.isNullOrEmpty() &&
        // track in {minor, patch} deviates from today's behavior. Spiget
        // has no channel concept, so there's no other existing "track"
        // branch to preserve here.
        val useTrackBound = pin.isNullOrEmpty() && (policy.track == "minor" || policy.track == "patch")
        val baseline = if (useTrackBound) {
            // The installed baseline's *human* version name -- versionName
            // in plugins-state.json is the version uuid, not a human name
            // (see the comment on that write() call below), so it isn't
            // semver-parseable directly. versionId (the numeric id) is also
            // recorded, so it's fetched back out here.
            val installedVersionId = PluginStateStore.read(serverDir, sourceName, id)?.versionId
            installedVersionId?.let { versionId ->
                try {
                    val installedMeta =
                        json.decodeFromString(SpigetVersionResponse.serializer(), get("/resources/$id/versions/$versionId"))
                    installedMeta.name?.let { SemVer.parse(it) }
                } catch (e: Exception) {
                    null
                }
            }
        } else {
            null
        }

        val targetVersion = when {
            !pin.isNullOrEmpty() && NUMERIC.matches(pin) -> try {
                json.decodeFromString(SpigetVersionResponse.serializer(), get("/resources/$id/versions/$pin"))
            } catch (e: SpigetAdapterException) {
                throw SpigetAdapterException("Could not fetch pinned version '$pin' for '$id'")
            }
            !pin.isNullOrEmpty() -> resolvePinnedVersion(id, pin)
            useTrackBound && baseline != null ->
                fetchVersionWithinBound(id, baseline, policy.track!!) ?: run {
                    Log.info("No $id version within the track=${policy.track} bound of the installed version was found")
                    return
                }
            else -> {
                if (useTrackBound) {
                    Log.debug("no semver baseline installed for \"$id\" yet; falling back to latest for track=${policy.track}")
                }
                try {
                    json.decodeFromString(SpigetVersionResponse.serializer(), get("/resources/$id/versions/latest"))
                } catch (e: SpigetAdapterException) {
                    throw SpigetAdapterException("Could not resolve latest version for '$id'")
                }
            }
        }

        val targetUuid = targetVersion.uuid
        val targetVersionId = targetVersion.id
        val targetName = targetVersion.name

        if (targetUuid.isNullOrEmpty() || targetVersionId == null) {
            throw SpigetAdapterException("Spiget returned no usable version for '$id'")
        }

        val displayVersion = targetName?.takeIf { it.isNotEmpty() } ?: targetUuid

        // version_name is the field a future PluginStateStore.read()
        // comparison reads back -- uuid (not the human-readable name, and
        // not the "deprecated" numeric id) is what's authoritative per the
        // research doc, so it's what's stored here, same role target_version
        // plays in HangarAdapter.
        val installed = PluginStateStore.read(serverDir, sourceName, id)?.versionName
        if (installed == targetUuid) {
            Log.info("\"$label\" is already up to date ($displayVersion)")
            return
        }

        val sanitized = name?.let { sanitizeFilename(it) }?.takeIf { it.isNotEmpty() } ?: "spiget-$id"
        var fileName = "$sanitized.jar"
        var target = pluginsDir.resolve(fileName)

        val temporary = Files.createTempFile(pluginsDir, ".spiget-download-", ".tmp")
        try {
            Log.info("Downloading $label $displayVersion")

            // Always the proxy endpoint -- the plain /download redirects to
            // a spigotmc.org HTML page, not a raw file (confirmed live; see
            // class doc). Rate-limited per Spiget's own docs, which is why
            // this only runs after the cheap metadata check above already
            // found a mismatch.
            val download = try {
                SharedHttp.download("$spigetApi/resources/$id/versions/$targetVersionId/download/proxy", temporary)
            } catch (e: IOException) {
                throw SpigetAdapterException("Download failed for '$id' $displayVersion")
            }

            if (download.size <= 0) {
                throw SpigetAdapterException("'$id' $displayVersion downloaded as an empty file")
            }

            // Best-effort, weak size check -- Spiget exposes no checksum at
            // all (see class doc). Only applied when the resource actually
            // reports a size; otherwise skipped outright rather than
            // failing on missing data.
            val resourceSize = resource.file?.size
            val resourceUnit = resource.file?.sizeUnit
            if (!resourceUnit.isNullOrEmpty() && resourceSize != null && resourceSize > 0) {
                val expectedBytes = sizeToBytes(resourceSize, resourceUnit)
                if (expectedBytes != null && abs(download.size - expectedBytes) > (expectedBytes * 0.5)) {
                    throw SpigetAdapterException(
                        "'$id' $displayVersion downloaded size (${download.size} bytes) is wildly different from Spiget's reported size (~$expectedBytes bytes)",
                    )
                }
            }

            // Log.info, not Log.warn: this was plain (stdout) println() before
            // this migration; the literal "Warning: " text is kept as-is
            // rather than doubled by Log.warn()'s own stderr-bound prefix.
            Log.info("Warning: Spiget exposes no checksum for any plugin -- \"$label\" $displayVersion was only verified by file size, not cryptographically")

            // Prefer the real filename the proxy reports over the sanitized
            // guess, when present.
            if (!download.fileName.isNullOrEmpty()) {
                fileName = download.fileName
                target = pluginsDir.resolve(fileName)
            }

            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)

            // The new version's filename may differ from what was
            // previously recorded (e.g. the proxy's reported filename
            // changes across versions) -- remove the now-stale jar only now
            // that the replacement is verified and on disk. Must run before
            // write() overwrites the old record.
            PluginStateStore.deleteStaleFile(serverDir, pluginsDir, sourceName, id, fileName)

            PluginStateStore.write(
                serverDir,
                InstalledVersion(
                    source = sourceName,
                    id = id,
                    versionName = targetUuid,
                    versionId = targetVersionId,
                    channelName = null,
                    sha256 = null,
                    size = download.size,
                    file = fileName,
                    external = false,
                    // Cached so `jarlet plugin list` can show the real
                    // plugin name instead of the bare numeric resource id
                    // -- no extra API call, `name` is already part of the
                    // resource response fetched above. Left null (not
                    // defaulted to `id`, unlike `label` above) when Spiget
                    // reports no name, so ListCommand can fall back to
                    // showing the bare id instead of a redundant "id (id)".
                    displayName = name?.takeIf { it.isNotEmpty() },
                ),
            )
        } finally {
            Files.deleteIfExists(temporary)
        }

        Log.info("Installed $label $displayVersion as $target")
        Log.info("No cryptographic checksum available for this source (size-verified only)")
    }

    @Serializable
    private data class SpigetResourceResponse(
        val external: Boolean? = false,
        val premium: Boolean? = false,
        val name: String? = null,
        val file: SpigetFile? = null,
    )

    @Serializable
    private data class SpigetFile(
        val size: Double? = null,
        val sizeUnit: String? = null,
        val externalUrl: String? = null,
    )

    @Serializable
    private data class SpigetVersionResponse(
        val uuid: String? = null,
        val id: Long? = null,
        val name: String? = null,
        val releaseDate: Long? = null,
    )
}
