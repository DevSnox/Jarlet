package me.devsnox.jarlet.adapter.plugin

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.devsnox.jarlet.Log
import me.devsnox.jarlet.config.SysConfig
import me.devsnox.jarlet.config.JarletToml
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

/** Thrown for Hangar request/auth/verification failures. */
class HangarAdapterException(message: String) : Exception(message)

/**
 * Hangar (PaperMC's own first-party plugin repository) plugin source
 * adapter. Notable API quirks this accounts for:
 *
 *   - Hangar requires an authenticated JWT for essentially every endpoint
 *     (unlike [SpigetAdapter]'s fully anonymous API) -- [authenticate]
 *     exchanges `JARLET_HANGAR_API_KEY` for a short-lived JWT, [get]
 *     re-authenticates once and retries on a 401 (expired/invalid JWT).
 *   - The JWT is process-scoped only, seeded from `JARLET_HANGAR_JWT` if
 *     the environment already provides one; a freshly minted JWT is never
 *     written back out to the environment.
 *   - Hangar's `reviewState` enum (`unreviewed`/`reviewed`/`under_review`/
 *     `partially_reviewed`) has no "rejected" state -- `visibility` is what
 *     actually gates public availability, so any known `reviewState` is
 *     fine; this only skips on an unrecognized value.
 *   - A version's `downloads.PAPER.fileInfo` and `.externalUrl` are
 *     confirmed live to be mutually exclusive on real data (Geyser:
 *     `fileInfo=null` whenever `externalUrl` is set) -- but `fileInfo` is
 *     still read and passed through to [UntrustedExternalDownloader] in
 *     case that ever isn't true for some other project.
 *   - `/projects/{slug}/latest?channel=...` returns a plain-text version
 *     name, not a JSON-wrapped one -- read as the raw response body, not
 *     decoded as JSON.
 */
object HangarAdapter : PluginSourceAdapter {
    override val sourceName: String = "hangar"
    override val displayName: String = "Hangar"

    private val VALID_SLUG = Regex("^[0-9A-Za-z._-]+$")
    private val RECOGNIZED_REVIEW_STATES =
        setOf("", "unreviewed", "reviewed", "under_review", "partially_reviewed")

    private val json = Json { ignoreUnknownKeys = true }

    private val hangarApi: String by lazy { SysConfig.default().value("HANGAR_API") }
    private val envFilePath: String by lazy { SysConfig.default().value("ENV_FILE_PATH") }
    private val versionListLimit: Int by lazy { SysConfig.default().value("HANGAR_VERSION_LIST_LIMIT").toInt() }
    private val versionListMaxPages: Int by lazy { SysConfig.default().value("HANGAR_VERSION_LIST_MAX_PAGES").toInt() }

    /** In-memory JWT for this process only -- never round-trips back out to the environment. */
    private var jwt: String? = System.getenv("JARLET_HANGAR_JWT")?.takeIf { it.isNotEmpty() }

    private fun authenticate() {
        val apiKey = System.getenv("JARLET_HANGAR_API_KEY")?.takeIf { it.isNotEmpty() }
            ?: throw HangarAdapterException(
                "JARLET_HANGAR_API_KEY is not set. Export it (export JARLET_HANGAR_API_KEY='<your-hangar-api-key>') or set it permanently in $envFilePath",
            )

        val response = try {
            SharedHttp.post("$hangarApi/authenticate", mapOf("apiKey" to apiKey))
        } catch (e: IOException) {
            throw HangarAdapterException("Hangar authentication failed")
        }

        if (response.status !in 200..299) {
            throw HangarAdapterException("Hangar authentication failed")
        }

        val token = try {
            json.decodeFromString(HangarAuthResponse.serializer(), response.body).token
        } catch (e: Exception) {
            null
        }

        if (token.isNullOrEmpty()) {
            throw HangarAdapterException("Hangar authentication did not return a token")
        }

        jwt = token
    }

    /** Authenticated GET against `$HANGAR_API$path`. Re-authenticates once and retries on a 401. */
    private fun get(path: String): String {
        if (jwt.isNullOrEmpty()) authenticate()

        fun attempt(): SharedHttp.Response =
            try {
                SharedHttp.get("$hangarApi$path", mapOf("Authorization" to "HangarAuth $jwt"))
            } catch (e: IOException) {
                throw HangarAdapterException("Hangar request failed: $path")
            }

        var response = attempt()
        if (response.status == 401) {
            authenticate()
            response = attempt()
        }

        if (response.status !in 200..299) {
            throw HangarAdapterException("Hangar request to $path failed with HTTP ${response.status}")
        }

        return response.body
    }

    override fun process(
        serverDir: Path,
        pluginsDir: Path,
        id: String,
        policy: JarletToml.Policy,
        trustRequested: Boolean,
    ) {
        val slug = id
        if (!VALID_SLUG.matches(slug)) {
            throw HangarAdapterException("Invalid Hangar project slug: $slug")
        }

        val project = try {
            json.decodeFromString(HangarProjectResponse.serializer(), get("/projects/$slug"))
        } catch (e: HangarAdapterException) {
            throw e
        } catch (e: Exception) {
            throw HangarAdapterException("Could not fetch Hangar project '$slug'")
        }

        if (!project.visibility.isNullOrEmpty() && project.visibility != "public") {
            Log.info("Skipping \"$slug\": project visibility is \"${project.visibility}\" (not public)")
            return
        }

        val channel = policy.channel ?: "Release"

        // Gated the same way as GithubAdapter/SpigetAdapter: only
        // pin.isNullOrEmpty() && track in {minor, patch} takes the
        // version-list path; everything else uses /latest?channel=X.
        val useTrackBound = policy.pin.isNullOrEmpty() && (policy.track == "minor" || policy.track == "patch")
        val baseline = if (useTrackBound) {
            PluginStateStore.read(serverDir, sourceName, slug)?.versionName?.let { SemVer.parse(it) }
        } else {
            null
        }

        val targetVersion = when {
            !policy.pin.isNullOrEmpty() -> policy.pin
            useTrackBound && baseline != null -> {
                val entries = fetchVersionListEntries(slug, channel)
                if (entries == null) {
                    // Any network/parse failure from the version-list
                    // endpoint degrades gracefully rather than breaking
                    // update/start for every Hangar-sourced plugin using
                    // minor/patch tracking.
                    Log.debug(
                        "could not use Hangar's version list for \"$slug\" (track=${policy.track}); falling back to latest for channel",
                    )
                    get("/projects/$slug/latest?channel=$channel").trim()
                } else {
                    val eligible = entries.filter { (it.reviewState ?: "") in RECOGNIZED_REVIEW_STATES }
                    val picked = pickHighestWithinBound(eligible, { it.name ?: "" }, baseline, policy.track!!)
                    if (picked == null) {
                        Log.info("No $slug version within the track=${policy.track} bound of the installed version was found")
                        return
                    }
                    // Non-null: any entry that survived pickHighestWithinBound
                    // matched SemVer.parse(it.name ?: ""), which only ever
                    // succeeds for a non-null, non-empty name.
                    picked.name!!
                }
            }
            else -> {
                if (useTrackBound) {
                    Log.debug("no semver baseline installed for \"$slug\" yet; falling back to latest for channel=$channel")
                }
                get("/projects/$slug/latest?channel=$channel").trim()
            }
        }

        if (targetVersion.isEmpty()) {
            throw HangarAdapterException("Hangar returned no version for '$slug'")
        }

        val installed = PluginStateStore.read(serverDir, sourceName, slug)?.versionName
        if (installed == targetVersion) {
            Log.info("\"$slug\" is already up to date ($targetVersion)")
            return
        }

        val version = try {
            json.decodeFromString(HangarVersionResponse.serializer(), get("/projects/$slug/versions/$targetVersion"))
        } catch (e: HangarAdapterException) {
            throw e
        } catch (e: Exception) {
            throw HangarAdapterException("Could not fetch version '$targetVersion' for '$slug'")
        }

        if (!version.visibility.isNullOrEmpty() && version.visibility != "public") {
            Log.info("Skipping \"$slug\" $targetVersion: version visibility is \"${version.visibility}\"")
            return
        }

        val reviewState = version.reviewState ?: ""
        if (reviewState !in RECOGNIZED_REVIEW_STATES) {
            Log.info("Skipping \"$slug\" $targetVersion: review state is \"$reviewState\", not a recognized state")
            return
        }

        val paperDownload = version.downloads?.get("PAPER")
        val externalUrl = paperDownload?.externalUrl

        if (!externalUrl.isNullOrEmpty()) {
            val redirect = ExternalUrlRedirector.tryResolve(externalUrl)
            if (redirect != null) {
                Log.info(
                    "\"$slug\" $targetVersion is hosted externally at $externalUrl -- redirecting to ${redirect.id} (${redirect.source})",
                )
                ExternalUrlRedirector.dispatch(redirect, serverDir, pluginsDir, trustRequested)
                return
            }

            val fileInfo = paperDownload.fileInfo
            UntrustedExternalDownloader.handle(
                serverDir = serverDir,
                pluginsDir = pluginsDir,
                source = sourceName,
                id = slug,
                label = slug,
                externalUrl = externalUrl,
                expectedHash = fileInfo?.sha256Hash,
                expectedSize = fileInfo?.sizeBytes,
                fallbackFilename = "$slug.jar",
                trustRequested = trustRequested,
                versionName = targetVersion,
                channelName = version.channel?.name,
            )
            return
        }

        val fileInfo = paperDownload?.fileInfo
        val expectedHash = fileInfo?.sha256Hash
        val expectedSize = fileInfo?.sizeBytes

        if (expectedHash.isNullOrEmpty() || expectedSize == null) {
            throw HangarAdapterException("No verifiable Hangar-hosted download found for '$slug' $targetVersion")
        }

        val fileName = fileInfo.name?.takeIf { it.isNotEmpty() } ?: "$slug.jar"

        val target = pluginsDir.resolve(fileName)
        val temporary = Files.createTempFile(pluginsDir, ".hangar-download-", ".tmp")
        try {
            Log.info("Downloading $slug $targetVersion")

            if (jwt.isNullOrEmpty()) authenticate()

            val download = try {
                SharedHttp.download(
                    "$hangarApi/projects/$slug/versions/$targetVersion/PAPER/download",
                    temporary,
                    mapOf("Authorization" to "HangarAuth $jwt"),
                )
            } catch (e: IOException) {
                throw HangarAdapterException("Download failed for '$slug' $targetVersion")
            }

            if (download.size != expectedSize) {
                throw HangarAdapterException("'$slug' $targetVersion has the wrong size")
            }

            val actualHash = SharedHttp.sha256Hex(temporary)
            if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                throw HangarAdapterException("'$slug' $targetVersion SHA-256 verification failed")
            }

            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }

        // The new version's filename may differ from what was previously
        // recorded (e.g. an embedded version number bump) -- remove the
        // now-stale jar only now that the replacement is verified and on
        // disk. Must run before write() overwrites the old record.
        PluginStateStore.deleteStaleFile(serverDir, pluginsDir, sourceName, slug, fileName)

        PluginStateStore.write(
            serverDir,
            InstalledVersion(
                source = sourceName,
                id = slug,
                versionName = targetVersion,
                versionId = version.id,
                channelName = version.channel?.name,
                sha256 = expectedHash,
                size = expectedSize,
                file = fileName,
                external = false,
            ),
        )

        Log.info("Installed $slug $targetVersion as $target")
        Log.info("SHA-256: $expectedHash")
    }

    /**
     * Best-effort fetch of Hangar's project version *list* endpoint --
     * `GET /projects/{slug}/versions?limit=X&offset=Y&channel=Z`, paginated
     * up to [versionListMaxPages] pages of [versionListLimit] each (config
     * values, `jarlet-sys.conf`). Assumes a `{ pagination: {...}, result:
     * [...] }` wrapper shape. Any network or decode failure is caught and
     * folded into a `null` return -- callers fall back to
     * `/latest?channel=X` rather than propagating a hard failure, so a
     * shape mismatch degrades gracefully instead of breaking every
     * Hangar-sourced minor/patch-tracked plugin.
     */
    private fun fetchVersionListEntries(slug: String, channel: String): List<HangarVersionListEntry>? =
        try {
            val entries = mutableListOf<HangarVersionListEntry>()
            for (page in 0 until versionListMaxPages) {
                val offset = page * versionListLimit
                val body = get("/projects/$slug/versions?limit=$versionListLimit&offset=$offset&channel=$channel")
                val parsed = json.decodeFromString(HangarVersionListResponse.serializer(), body)
                entries += parsed.result

                // Fewer than a full page means we've reached the end of the list.
                if (parsed.result.size < versionListLimit) break
            }
            entries
        } catch (e: Exception) {
            null
        }

    @Serializable
    private data class HangarAuthResponse(val token: String? = null)

    @Serializable
    private data class HangarProjectResponse(val visibility: String? = null)

    @Serializable
    private data class HangarVersionResponse(
        val id: Long? = null,
        val visibility: String? = null,
        val reviewState: String? = null,
        val channel: HangarChannel? = null,
        val downloads: Map<String, HangarDownload>? = null,
    )

    @Serializable
    private data class HangarChannel(val name: String? = null)

    /**
     * Response wrapper for the version-*list* endpoint -- follows Hangar
     * API v1's paginated-list shape (`{ pagination: {...}, result: [...] }`);
     * `pagination` itself is never read since page-end is instead detected
     * the same way [SpigetAdapter.resolvePinnedVersion] does (a short page).
     */
    @Serializable
    private data class HangarVersionListResponse(val result: List<HangarVersionListEntry> = emptyList())

    /** One entry of [HangarVersionListResponse.result]. */
    @Serializable
    private data class HangarVersionListEntry(
        val name: String? = null,
        val channel: HangarChannel? = null,
        val visibility: String? = null,
        val reviewState: String? = null,
    )

    @Serializable
    private data class HangarDownload(
        val externalUrl: String? = null,
        val fileInfo: HangarFileInfo? = null,
    )

    @Serializable
    private data class HangarFileInfo(
        val name: String? = null,
        val sha256Hash: String? = null,
        val sizeBytes: Long? = null,
    )
}
