package me.devsnox.jarlet.adapter.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.devsnox.jarlet.config.JarletVersion
import me.devsnox.jarlet.config.SysConfig
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Thrown for the same failure cases `fail()` covers throughout `src/adapter/server/paper.sh`. */
class PaperAdapterException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Paper server-software adapter -- Kotlin port of `src/adapter/server/paper.sh`.
 *
 * Queries `PAPER_API` (fill.papermc.io's v3 API, see `jarlet-sys.conf`) for
 * the highest-numbered `STABLE` build of a given Minecraft version,
 * downloads its `server:default` artifact, and verifies both size and
 * SHA-256 before installing it at the requested [Path] -- the same
 * validation sequence as `install_paper_server()`.
 *
 * Uses `java.net.http.HttpClient` (JDK built-in, no extra dependency) for
 * HTTP in place of `curl`, and `kotlinx.serialization` for JSON in place
 * of `jq` -- both per the migration plan's recommended defaults.
 */
object PaperAdapter : ServerSoftwareAdapter {
    override val id: String = "paper"

    private const val DOWNLOAD_RETRIES = 3

    private val VALID_VERSION = Regex("^[0-9A-Za-z._-]+$")

    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient: HttpClient by lazy {
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    /** `PROJECT_NAME/<jarlet-version> (REPO_URL)` -- mirrors install.sh's `USER_AGENT`. */
    private val userAgent: String by lazy {
        val sysConfig = SysConfig.default()
        "${sysConfig.value("PROJECT_NAME")}/${JarletVersion.VERSION} (${sysConfig.value("REPO_URL")})"
    }

    private val paperApi: String by lazy { SysConfig.default().value("PAPER_API") }

    override fun install(minecraftVersion: String, target: Path) {
        if (!VALID_VERSION.matches(minecraftVersion)) {
            throw PaperAdapterException("Invalid Minecraft version: $minecraftVersion")
        }

        val project = fetchProject()
        val supported = project.versions.values.any { it.contains(minecraftVersion) }
        if (!supported) {
            throw PaperAdapterException("Paper does not support Minecraft $minecraftVersion")
        }

        val build = fetchBuilds(minecraftVersion)
            .filter { it.channel == "STABLE" && it.downloads.containsKey("server:default") }
            .maxByOrNull { it.id }
            ?: throw PaperAdapterException("No stable Paper build exists for Minecraft $minecraftVersion")

        val download = build.downloads.getValue("server:default")

        val targetDir = (target.toAbsolutePath().parent)
            ?: throw PaperAdapterException("Invalid install target: $target")
        Files.createDirectories(targetDir)

        val temporary = Files.createTempFile(targetDir, ".paper-download-", ".tmp")
        try {
            println("Downloading Paper $minecraftVersion build ${build.id}")

            downloadTo(download.url, temporary)

            val actualSize = Files.size(temporary)
            if (actualSize != download.size) {
                throw PaperAdapterException("Paper download has the wrong size")
            }

            val actualHash = sha256Hex(temporary)
            if (!actualHash.equals(download.checksums.sha256, ignoreCase = true)) {
                throw PaperAdapterException("Paper SHA-256 verification failed")
            }

            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }

        println("Installed ${download.name} as $target")
        println("SHA-256: ${download.checksums.sha256}")
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
     * Fetches [url] as text, folding every failure mode into a [PaperAdapterException] whose
     * message is prefixed with [errorPrefix] but distinguishes *why* -- network error, a
     * non-2xx status (with the code), or an empty body -- since callers previously couldn't
     * tell these apart.
     */
    private fun get(url: String, errorPrefix: String): String {
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", userAgent)
            .GET()
            .build()

        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            throw PaperAdapterException("$errorPrefix: network error", cause = e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw PaperAdapterException("$errorPrefix: interrupted", cause = e)
        }

        if (response.statusCode() !in 200..299) {
            throw PaperAdapterException("$errorPrefix: HTTP ${response.statusCode()}")
        }

        val body = response.body()
        if (body.isNullOrBlank()) {
            throw PaperAdapterException("$errorPrefix: empty response")
        }

        return body
    }

    /** Mirrors `curl --retry 3` around the actual jar download (only this step retries, matching install.sh). */
    private fun downloadTo(url: String, target: Path) {
        var lastError: Exception? = null

        repeat(DOWNLOAD_RETRIES) {
            try {
                val request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", userAgent)
                    .GET()
                    .build()
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(target))
                if (response.statusCode() !in 200..299) {
                    throw IOException("HTTP ${response.statusCode()}")
                }
                return
            } catch (e: Exception) {
                lastError = e
            }
        }

        throw PaperAdapterException("Paper download failed" + (lastError?.message?.let { ": $it" } ?: ""))
    }

    private fun sha256Hex(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Serializable
    private data class PaperProjectResponse(
        val project: String? = null,
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
