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

/** Thrown for PaperMC repository request, download, or verification failures. */
class PaperMcAdapterException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Shared adapter for server packages published by PaperMC's downloads
 * service. Paper uses Minecraft versions as its package baseline; Velocity
 * uses its own release version directly. The repository lookup, artifact
 * verification, and atomic installation are shared.
 */
object PaperMcAdapter : ServerSoftwareAdapter {
    override val supportedPackages: Set<String> = setOf("paper", "velocity")

    private val validVersion = Regex("^[0-9A-Za-z._-]+$")
    private val json = Json { ignoreUnknownKeys = true }
    private val api: String by lazy { SysConfig.default().value("PAPERMC_API") }

    override fun isMinecraftServer(packageName: String): Boolean = packageName == "paper"

    override fun launchArguments(packageName: String): List<String> =
        if (packageName == "paper") listOf("nogui") else emptyList()

    override fun install(
        packageName: String,
        packageVersion: String,
        target: Path,
        policy: JarletToml.Policy,
    ): String {
        if (packageName !in supportedPackages) {
            throw PaperMcAdapterException("Unsupported PaperMC package: $packageName")
        }
        if (!validVersion.matches(packageVersion)) {
            throw PaperMcAdapterException("Invalid $packageName package version: $packageVersion")
        }

        val project = fetchProject(packageName)
        val requestedVersion = resolveVersion(packageName, packageVersion, policy, project)
        val build = fetchBuilds(packageName, requestedVersion)
            .filter { it.channel == "STABLE" && it.downloads.containsKey("server:default") }
            .maxByOrNull { it.id }
            ?: throw PaperMcAdapterException("No stable $packageName build exists for $requestedVersion")
        val download = build.downloads.getValue("server:default")

        installArtifact(packageName, requestedVersion, build.id, download, target)
        return requestedVersion
    }

    private fun resolveVersion(
        packageName: String,
        packageVersion: String,
        policy: JarletToml.Policy,
        project: PaperProjectResponse,
    ): String {
        if (packageName == "velocity") return packageVersion

        if (!project.versions.values.any { it.contains(packageVersion) }) {
            throw PaperMcAdapterException("Paper does not support Minecraft $packageVersion")
        }

        val baseline = SemVer.parse(packageVersion)
        return if (baseline != null && (policy.track == "minor" || policy.track == "patch")) {
            pickHighestWithinBound(
                project.versions.values.flatten(),
                { it },
                baseline,
                policy.track,
            ) ?: packageVersion
        } else {
            packageVersion
        }
    }

    private fun fetchProject(packageName: String): PaperProjectResponse {
        val body = get(
            "$api/projects/$packageName",
            "Could not query $packageName versions",
        )
        return try {
            json.decodeFromString(PaperProjectResponse.serializer(), body)
        } catch (exception: kotlinx.serialization.SerializationException) {
            throw PaperMcAdapterException("Could not query $packageName versions: malformed response", exception)
        }
    }

    private fun fetchBuilds(packageName: String, packageVersion: String): List<PaperBuild> {
        val body = get(
            "$api/projects/$packageName/versions/$packageVersion/builds",
            "Could not query $packageName builds for $packageVersion",
        )
        return try {
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(PaperBuild.serializer()), body)
        } catch (exception: kotlinx.serialization.SerializationException) {
            throw PaperMcAdapterException(
                "Could not query $packageName builds for $packageVersion: malformed response",
                exception,
            )
        }
    }

    private fun installArtifact(
        packageName: String,
        packageVersion: String,
        buildId: Long,
        download: PaperDownload,
        target: Path,
    ) {
        val targetDir = target.toAbsolutePath().parent
            ?: throw PaperMcAdapterException("Invalid install target: $target")
        Files.createDirectories(targetDir)
        val temporary = Files.createTempFile(targetDir, ".papermc-download-", ".tmp")
        try {
            Log.info("Downloading $packageName $packageVersion build $buildId")
            try {
                SharedHttp.download(download.url, temporary)
            } catch (exception: IOException) {
                throw PaperMcAdapterException("$packageName download failed: ${exception.message}", exception)
            }

            if (Files.size(temporary) != download.size) {
                throw PaperMcAdapterException("$packageName download has the wrong size")
            }
            val actualHash = SharedHttp.sha256Hex(temporary)
            if (!actualHash.equals(download.checksums.sha256, ignoreCase = true)) {
                throw PaperMcAdapterException("$packageName SHA-256 verification failed")
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }

        Log.info("Installed ${download.name} as $target")
        Log.info("SHA-256: ${download.checksums.sha256}")
    }

    private fun get(url: String, errorPrefix: String): String {
        val response = try {
            SharedHttp.get(url)
        } catch (exception: IOException) {
            throw PaperMcAdapterException("$errorPrefix: network error", exception)
        }
        if (response.status !in 200..299) {
            throw PaperMcAdapterException("$errorPrefix: HTTP ${response.status}")
        }
        if (response.body.isBlank()) {
            throw PaperMcAdapterException("$errorPrefix: empty response")
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
