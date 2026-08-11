package me.devsnox.jarlet.adapter.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.devsnox.jarlet.Log
import me.devsnox.jarlet.config.JarletToml
import me.devsnox.jarlet.config.SysConfig
import me.devsnox.jarlet.lib.SemVer
import me.devsnox.jarlet.lib.SharedHttp
import me.devsnox.jarlet.lib.pickHighestWithinBound
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Thrown for Paper request/download/verification failures. */
class PaperAdapterException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Paper server-software adapter.
 *
 * Queries `PAPER_API` (fill.papermc.io's v3 API, see `jarlet-sys.conf`) for
 * the highest-numbered `STABLE` build of a given Minecraft version,
 * downloads its `server:default` artifact, and verifies both size and
 * SHA-256 before installing it at the requested [Path].
 *
 * HTTP GET, retried download (with truncated-transfer detection), and
 * SHA-256 hashing all delegate to [SharedHttp] -- the same plumbing the
 * plugin source adapters use, rather than a second copy of that code here.
 */
object PaperAdapter : ServerSoftwareAdapter {
    override val id: String = "paper"

    private val VALID_VERSION = Regex("^[0-9A-Za-z._-]+$")

    private val json = Json { ignoreUnknownKeys = true }

    private val paperApi: String by lazy { SysConfig.default().value("PAPER_API") }

    override fun install(minecraftVersion: String, target: Path, policy: JarletToml.Policy): String {
        if (!VALID_VERSION.matches(minecraftVersion)) {
            throw PaperAdapterException("Invalid Minecraft version: $minecraftVersion")
        }

        val project = fetchProject()
        val supported = project.versions.values.any { it.contains(minecraftVersion) }
        if (!supported) {
            throw PaperAdapterException("Paper does not support Minecraft $minecraftVersion")
        }

        // Under track = "minor"/"patch", `minecraftVersion` is a movable
        // baseline, not a fixed target -- resolve the highest MC version
        // Paper supports within that bound (no new HTTP call: `project`
        // above already lists every version Paper supports). Every other
        // policy shape (pin, track=latest/channel, no policy) resolves to
        // exactly `minecraftVersion` unchanged.
        val baseline = SemVer.parse(minecraftVersion)
        val resolvedVersion = if (baseline != null && (policy.track == "minor" || policy.track == "patch")) {
            val candidates = project.versions.values.flatten()
            // Inclusive of the baseline itself (see SemVer.bounds), and
            // minecraftVersion is already confirmed present in `candidates`
            // by the support-check above -- so this can only be null if
            // minecraftVersion itself somehow failed to parse, which
            // [baseline] already ruled out. The null-coalesce is a pure
            // defensive fallback, not an expected path.
            pickHighestWithinBound(candidates, { it }, baseline, policy.track) ?: minecraftVersion
        } else {
            minecraftVersion
        }

        val build = fetchBuilds(resolvedVersion)
            .filter { it.channel == "STABLE" && it.downloads.containsKey("server:default") }
            .maxByOrNull { it.id }
            ?: throw PaperAdapterException("No stable Paper build exists for Minecraft $resolvedVersion")

        val download = build.downloads.getValue("server:default")

        val targetDir = (target.toAbsolutePath().parent)
            ?: throw PaperAdapterException("Invalid install target: $target")
        Files.createDirectories(targetDir)

        val temporary = Files.createTempFile(targetDir, ".paper-download-", ".tmp")
        try {
            Log.info("Downloading Paper $resolvedVersion build ${build.id}")

            try {
                SharedHttp.download(download.url, temporary)
            } catch (e: IOException) {
                throw PaperAdapterException("Paper download failed: ${e.message}", cause = e)
            }

            val actualSize = Files.size(temporary)
            if (actualSize != download.size) {
                throw PaperAdapterException("Paper download has the wrong size")
            }

            val actualHash = SharedHttp.sha256Hex(temporary)
            if (!actualHash.equals(download.checksums.sha256, ignoreCase = true)) {
                throw PaperAdapterException("Paper SHA-256 verification failed")
            }

            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }

        Log.info("Installed ${download.name} as $target")
        Log.info("SHA-256: ${download.checksums.sha256}")

        return resolvedVersion
    }

    private fun fetchProject(): PaperProjectResponse {
        val body = get("$paperApi/projects/paper", "Could not query Paper versions")
        return try {
            json.decodeFromString(PaperProjectResponse.serializer(), body)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw PaperAdapterException("Could not query Paper versions: malformed response", cause = e)
        }
    }

    private fun fetchBuilds(minecraftVersion: String): List<PaperBuild> {
        val body = get(
            "$paperApi/projects/paper/versions/$minecraftVersion/builds",
            "Could not query builds for Minecraft $minecraftVersion",
        )
        return try {
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(PaperBuild.serializer()), body)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw PaperAdapterException(
                "Could not query builds for Minecraft $minecraftVersion: malformed response",
                cause = e,
            )
        }
    }

    /**
     * Fetches [url] as text via [SharedHttp.get], folding every failure mode into a
     * [PaperAdapterException] whose message is prefixed with [errorPrefix] but distinguishes
     * *why* -- network error, a non-2xx status (with the code), or an empty body -- since
     * callers previously couldn't tell these apart.
     */
    private fun get(url: String, errorPrefix: String): String {
        val response = try {
            SharedHttp.get(url)
        } catch (e: IOException) {
            throw PaperAdapterException("$errorPrefix: network error", cause = e)
        }

        if (response.status !in 200..299) {
            throw PaperAdapterException("$errorPrefix: HTTP ${response.status}")
        }

        if (response.body.isBlank()) {
            throw PaperAdapterException("$errorPrefix: empty response")
        }

        return response.body
    }

    @Serializable
    private data class PaperProjectResponse(
        val versions: Map<String, List<String>> = emptyMap(),
    )

    @Serializable
    private data class PaperBuild(
        val id: Long,
        val channel: String,
        val downloads: Map<String, PaperDownload> = emptyMap(),
    )

    @Serializable
    private data class PaperDownload(
        val name: String,
        val url: String,
        val checksums: PaperChecksums,
        val size: Long,
    )

    @Serializable
    private data class PaperChecksums(
        val sha256: String,
    )
}
