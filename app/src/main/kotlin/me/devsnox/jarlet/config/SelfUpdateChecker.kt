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
import java.nio.file.Path

/**
 * Notify-only check for a newer stable Jarlet release on GitHub, shared by
 * two callers: [maybeCheckAsync] (a background, non-blocking check fired on
 * every CLI invocation) and [versionMessage] (a synchronous check used by
 * `--version`/`-v`, since a user explicitly asking for version info can
 * reasonably wait on it). Both share one state file under [JarletHome]
 * caching the last check's timestamp and result, rate-limited to once per
 * [SysConfig]'s `SELF_UPDATE_CHECK_INTERVAL_MINUTES` -- whichever caller
 * runs first within that window does the real GitHub lookup; the other
 * reads the cached result instead of re-checking. A notice is only ever
 * shown when a strictly newer release exists with its Linux x86_64 asset
 * actually published -- any network, parse, or missing-asset condition is
 * silently treated as "nothing to report". Never installs or invokes
 * anything itself.
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

    private fun stateFile(): Path = JarletHome.resolve().resolve("update-check-state")

    /** `timestamp` on the first line, the cached notice (possibly blank -- meaning "checked, nothing to report") on the second. */
    private fun readState(path: Path): Pair<Long, String?>? {
        return try {
            if (!Files.isRegularFile(path)) return null
            val lines = Files.readAllLines(path)
            val timestamp = lines.getOrNull(0)?.toLongOrNull() ?: return null
            val notice = lines.getOrNull(1)?.takeIf { it.isNotBlank() }
            timestamp to notice
        } catch (e: Exception) {
            null
        }
    }

    private fun writeState(path: Path, timestamp: Long, notice: String?) {
        try {
            Files.createDirectories(path.parent)
            Files.writeString(path, "$timestamp\n${notice.orEmpty()}\n")
        } catch (e: Exception) {
            // Best-effort cache -- a failed write just means the next
            // invocation re-checks instead of using a cached result.
        }
    }

    private fun isFresh(lastChecked: Long, now: Long, intervalMinutes: Long): Boolean =
        now - lastChecked < intervalMinutes * 60_000

    /**
     * Entry point called once per CLI invocation. No-ops immediately (no
     * thread spawned, no I/O) if `JARLET_NO_UPDATE_CHECK` is set, or if the
     * state file shows a check already happened within the configured
     * interval. Otherwise starts a daemon thread doing the actual network
     * call and caching its result; returns the thread so the caller can
     * join it with a bounded timeout.
     */
    fun maybeCheckAsync(): Thread? {
        if (!System.getenv("JARLET_NO_UPDATE_CHECK").isNullOrEmpty()) return null

        val intervalMinutes = SysConfig.default().value("SELF_UPDATE_CHECK_INTERVAL_MINUTES").toLongOrNull() ?: return null
        val path = stateFile()
        val now = System.currentTimeMillis()

        val state = readState(path)
        if (state != null && isFresh(state.first, now, intervalMinutes)) return null

        val thread = Thread {
            val notice = fetchAndEvaluate()
            writeState(path, System.currentTimeMillis(), notice)
            notice?.let { Log.info(it) }
        }
        thread.isDaemon = true
        thread.start()
        return thread
    }

    /**
     * Synchronous counterpart used by `--version`/`-v`: returns [version]
     * alone, or [version] plus the update notice on its own line when one
     * applies. Shares the same cache/interval/disable-flag as
     * [maybeCheckAsync] -- a fresh cached result (from a prior invocation,
     * background or explicit) is reused as-is; a stale or missing one
     * triggers one bounded, synchronous GitHub check (a user asking for
     * version info can reasonably wait on it, unlike a passing background
     * check).
     */
    fun versionMessage(version: String): String {
        val display = "jarlet version $version"
        if (!System.getenv("JARLET_NO_UPDATE_CHECK").isNullOrEmpty()) return display

        val intervalMinutes = SysConfig.default().value("SELF_UPDATE_CHECK_INTERVAL_MINUTES").toLongOrNull()
            ?: return display
        val path = stateFile()
        val now = System.currentTimeMillis()

        val state = readState(path)
        val notice = if (state != null && isFresh(state.first, now, intervalMinutes)) {
            state.second
        } else {
            fetchAndEvaluate().also { writeState(path, now, it) }
        }

        return if (notice != null) "$display\n$notice" else display
    }
}
