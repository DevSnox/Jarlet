package me.devsnox.jarlet.adapter.plugin

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.devsnox.jarlet.Log
import me.devsnox.jarlet.config.SysConfig
import me.devsnox.jarlet.config.JarletToml
import me.devsnox.jarlet.plugin.ExternalUrlRedirector
import me.devsnox.jarlet.config.InstalledVersion
import me.devsnox.jarlet.lib.SharedHttp
import me.devsnox.jarlet.plugin.PluginSourceAdapter
import me.devsnox.jarlet.config.PluginStateStore
import me.devsnox.jarlet.plugin.UntrustedExternalDownloader
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Thrown for the same failure cases `fail()` covers throughout `src/adapter/plugin/hangar.sh`. */
class HangarAdapterException(message: String) : Exception(message)

/**
 * Hangar (PaperMC's own first-party plugin repository) plugin source
 * adapter -- Kotlin port of `src/adapter/plugin/hangar.sh`. See that file's
 * header and `prototyping/documentation/sources/hangar-plugin-fetching.md`
 * for the full research this implements; notably:
 *
 *   - Hangar requires an authenticated JWT for essentially every endpoint
 *     (unlike [SpigetAdapter]'s fully anonymous API) -- [authenticate]
 *     exchanges `JARLET_HANGAR_API_KEY` for a short-lived JWT, [get]
 *     re-authenticates once and retries on a 401 (expired/invalid JWT).
 *   - The JWT is process-scoped only, seeded from `JARLET_HANGAR_JWT` if a
 *     wrapping shell session already exported one. Unlike the bash version,
 *     this does NOT re-export the freshly minted JWT back out under that
 *     env var after authenticating -- bash's `export` only ever affected
 *     the (rare) case where `jarlet` itself was sourced rather than
 *     executed into its own subprocess; a compiled JVM binary invoked as a
 *     normal child process has no equivalent mechanism to hand a value back
 *     to its parent shell, so that half of the behavior has no meaningful
 *     Kotlin equivalent and is dropped. Reading a pre-set
 *     `JARLET_HANGAR_JWT` is still honored.
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

    /** In-memory JWT for this process only -- see the class doc for why this never round-trips back out to the environment the way the bash version's `export` attempted to. */
    private var jwt: String? = System.getenv("JARLET_HANGAR_JWT")?.takeIf { it.isNotEmpty() }

    private fun authenticate() {
        val apiKey = System.getenv("JARLET_HANGAR_API_KEY")?.takeIf { it.isNotEmpty() }
            ?: throw HangarAdapterException(
                "JARLET_HANGAR_API_KEY is not set. Export your Hangar API key first: export JARLET_HANGAR_API_KEY='<your-hangar-api-key>'",
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

    /** Authenticated GET against `$HANGAR_API$path`. Re-authenticates once and retries on a 401. Mirrors `hangar_get()`. */
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
        val targetVersion = if (!policy.pin.isNullOrEmpty()) {
            policy.pin
        } else {
            get("/projects/$slug/latest?channel=$channel").trim()
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
