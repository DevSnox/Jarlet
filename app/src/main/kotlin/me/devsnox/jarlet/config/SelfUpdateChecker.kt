package me.devsnox.jarlet.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.devsnox.jarlet.Log
import me.devsnox.jarlet.lib.SemVer
import me.devsnox.jarlet.lib.SharedHttp
import java.nio.file.Files

/**
 * Background, notify-only check for a newer stable Jarlet release on
 * GitHub. [maybeCheckAsync] is the entry point: it rate-limits itself to
 * once per [SysConfig]'s `SELF_UPDATE_CHECK_INTERVAL_MINUTES` via a
 * timestamp file under [JarletHome], then runs the actual GitHub lookup on
 * a daemon thread so it never delays a command. The check only ever prints
 * a single-line notice through [Log.info] when a strictly newer release
 * exists with its Linux x86_64 asset actually published -- any network,
 * parse, or missing-asset condition is silently treated as "nothing to
 * report". Never installs or invokes anything itself.
 */
object SelfUpdateChecker {

    /**
     * Pure: given the currently running version, a release's tag, and the
     * set of asset names published on that release, returns the notice
     * string to print, or null if nothing should be shown (not newer, or
     * asset not published). No I/O, fully unit-testable without mocking.
     */
    internal fun evaluate(currentVersion: String, latestTag: String, assetNames: Set<String>, installUrl: String): String? {
        val current = SemVer.parse(currentVersion) ?: return null
        val latest = SemVer.parse(latestTag) ?: return null
        if (latest <= current) return null

        val assetName = "jarlet-$latestTag-${SysConfig.default().value("SELF_UPDATE_OS")}-${SysConfig.default().value("SELF_UPDATE_ARCH")}"
        if (assetName !in assetNames) return null

        return "A newer Jarlet release is available: $latestTag (current: $currentVersion). Update: curl -fsSL $installUrl | bash"
    }

    /** Best-effort, silent-on-any-failure GitHub fetch + evaluate; returns the notice or null. Never throws. */
    private fun fetchAndEvaluate(): String? {
        return try {
            val repo = SysConfig.default().value("SELF_UPDATE_REPO")
            val githubApi = SysConfig.default().value("GITHUB_API")
            val timeoutSeconds = SysConfig.default().value("SELF_UPDATE_HTTP_TIMEOUT_SECONDS").toLongOrNull()
            val response = SharedHttp.get("$githubApi/repos/$repo/releases/latest", timeoutSeconds = timeoutSeconds)
            if (response.status !in 200..299) return null

            val json = Json.parseToJsonElement(response.body).jsonObject
            val tag = json["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
            val assetNames = json["assets"]?.jsonArray
                ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
                ?.toSet() ?: emptySet()

            evaluate(JarletVersion.VERSION, tag, assetNames, SysConfig.default().value("SELF_UPDATE_INSTALL_URL"))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Entry point called once per CLI invocation. No-ops immediately (no
     * thread spawned, no I/O) if `JARLET_NO_UPDATE_CHECK` is set, or if the
     * state file shows a check already happened within the configured
     * interval. Otherwise updates the state file's timestamp immediately
     * (so overlapping/rapid invocations don't all fire a check) and starts
     * a daemon thread doing the actual network call; returns the thread so
     * the caller can join it with a bounded timeout.
     */
    fun maybeCheckAsync(): Thread? {
        if (!System.getenv("JARLET_NO_UPDATE_CHECK").isNullOrEmpty()) return null

        val stateFile = JarletHome.resolve().resolve("update-check-state")
        val intervalMinutes = SysConfig.default().value("SELF_UPDATE_CHECK_INTERVAL_MINUTES").toLongOrNull() ?: return null
        val now = System.currentTimeMillis()

        val lastChecked = try {
            if (Files.isRegularFile(stateFile)) Files.readString(stateFile).trim().toLongOrNull() else null
        } catch (e: Exception) {
            null
        }
        if (lastChecked != null && now - lastChecked < intervalMinutes * 60_000) return null

        try {
            Files.createDirectories(stateFile.parent)
            Files.writeString(stateFile, now.toString())
        } catch (e: Exception) {
            return null
        }

        val thread = Thread {
            fetchAndEvaluate()?.let { Log.info(it) }
        }
        thread.isDaemon = true
        thread.start()
        return thread
    }
}
