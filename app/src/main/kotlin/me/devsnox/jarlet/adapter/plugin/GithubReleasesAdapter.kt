package me.devsnox.jarlet.adapter.plugin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.devsnox.jarlet.config.JarletToml
import me.devsnox.jarlet.config.SysConfig
import me.devsnox.jarlet.plugin.InstalledVersion
import me.devsnox.jarlet.plugin.PluginHttp
import me.devsnox.jarlet.plugin.PluginSourceAdapter
import me.devsnox.jarlet.plugin.PluginStateStore
import me.devsnox.jarlet.plugin.PluginUrlMatcher
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Thrown for the same failure cases `fail()` covers throughout `src/adapter/plugin/github-releases.sh`. */
class GithubReleasesAdapterException(message: String) : Exception(message)

/**
 * GitHub Releases plugin source adapter -- Kotlin port of
 * `src/adapter/plugin/github-releases.sh`. See that file's header comment
 * for the full research this implements
 * (`prototyping/documentation/sources/github-releases-plugin-fetching.md`);
 * summarized here:
 *
 *   - [id] is `"owner/repo"` (e.g. `"ViaVersion/ViaVersion"`), not a
 *     searchable slug/resource id -- the caller must already know it.
 *   - [policy] is `{ pin = "<tag_name>" }` or `{ track = "latest" }` (no
 *     `channel` field -- GitHub's own `/releases/latest` already excludes
 *     prereleases/drafts).
 *   - A release's `assets` array has no "this is the plugin jar" field, so
 *     asset selection is a deterministic filter ([pickAsset]: content-type
 *     plus name-pattern exclusion). If more than one candidate survives --
 *     e.g. a core plugin plus optional addon jars in the same release, as
 *     with EssentialsX's 2.22.0 release -- the candidate with the highest
 *     value of the configured tie-break field (`GITHUB_ASSET_TIEBREAK_FIELD`
 *     in `jarlet-sys.conf`, default `"download_count"`) is picked
 *     automatically, on the assumption that the most-downloaded jar is the
 *     main/core artifact; no prompting, no persisted choice. Only skips
 *     (with a message) when zero candidates survive the filter at all.
 *   - GitHub's per-asset `digest` (`sha256:...`) is verified when present;
 *     falls back to size-only verification (with a printed warning) when
 *     absent, same "best available verification" precedent as
 *     [HangarAdapter].
 *
 * Assets are decoded as raw [JsonObject]s rather than a typed data class
 * because the tie-break field name itself is configurable
 * (`GITHUB_ASSET_TIEBREAK_FIELD`) and looked up dynamically -- mirroring
 * `github_releases_pick_asset()`'s `jq --arg field ... max_by(.[$field])`,
 * which is genuinely dynamic, not hardcoded to `download_count`.
 */
object GithubReleasesAdapter : PluginSourceAdapter, PluginUrlMatcher {
    override val sourceName: String = "github-releases"
    override val displayName: String = "Github"

    private val VALID_ID = Regex("^[0-9A-Za-z._-]+/[0-9A-Za-z._-]+$")

    private val SOURCE_JAR = Regex("""-sources\.jar$""", RegexOption.IGNORE_CASE)
    private val JAVADOC_JAR = Regex("""-javadoc\.jar$""", RegexOption.IGNORE_CASE)
    private val SHA256_FILE = Regex("""\.sha256$""", RegexOption.IGNORE_CASE)
    private val ASC_FILE = Regex("""\.asc$""", RegexOption.IGNORE_CASE)
    private val CHANGELOG_FILE = Regex("""^changelog""", RegexOption.IGNORE_CASE)
    private val TXT_FILE = Regex("""\.txt$""", RegexOption.IGNORE_CASE)

    private val CANDIDATE_CONTENT_TYPES =
        setOf("application/java-archive", "application/zip", "application/octet-stream")

    private val URL_TAG = Regex(
        """^https?://github\.com/([0-9A-Za-z._-]+)/([0-9A-Za-z._-]+)/releases/tag/([^/\s]+)/?$""",
    )
    private val URL_DOWNLOAD = Regex(
        """^https?://github\.com/([0-9A-Za-z._-]+)/([0-9A-Za-z._-]+)/releases/download/([^/\s]+)/([^/\s]+)/?$""",
    )
    private val URL_REPO = Regex("""^https?://github\.com/([0-9A-Za-z._-]+)/([0-9A-Za-z._-]+)/?$""")

    private val json = Json { ignoreUnknownKeys = true }

    private val githubApi: String by lazy { SysConfig.default().value("GITHUB_API") }
    private val tiebreakField: String by lazy { SysConfig.default().value("GITHUB_ASSET_TIEBREAK_FIELD") }

    /** `JARLET_GITHUB_TOKEN`, if set -- raises the unauthenticated 60 req/hr limit to 5000 req/hr when supplied. Never written to disk, process-scoped only, same treatment [HangarAdapter] gives its JWT. */
    private fun authHeaders(): Map<String, String> {
        val token = System.getenv("JARLET_GITHUB_TOKEN")
        val headers = mutableMapOf("Accept" to "application/vnd.github+json")
        if (!token.isNullOrEmpty()) headers["Authorization"] = "Bearer $token"
        return headers
    }

    /**
     * Recognizes GitHub repo/release URLs -- see the class doc and
     * `src/adapter/plugin/github-releases.sh`'s `github_releases_match_url()`
     * for the exact shapes recognized (with or without a trailing slash,
     * `http://`/`https://`; `www.github.com` is never used by GitHub
     * itself, so not matched):
     *   `github.com/{owner}/{repo}`
     *   `github.com/{owner}/{repo}/releases/tag/{tag}`
     *   `github.com/{owner}/{repo}/releases/download/{tag}/{asset-filename}`
     *
     * On a match, `Match.id` is `"{owner}/{repo}"` and `Match.policy` is
     * `{ pin = "{tag}" }` for a `.../releases/tag/{tag}` or
     * `.../releases/download/{tag}/{asset}` URL (an explicit version was
     * named either way -- the asset filename itself is not parsed or
     * trusted; [process] re-fetches the release by tag and re-runs its own
     * asset selection), or an empty policy (track latest -- [process] only
     * ever reads `.pin` from the policy, so an empty policy already means
     * "latest") otherwise.
     */
    override fun match(url: String): PluginUrlMatcher.Match? {
        URL_TAG.matchEntire(url)?.let { m ->
            val (owner, repo, tag) = m.destructured
            return PluginUrlMatcher.Match("$owner/$repo", JarletToml.Plugin.Policy(pin = tag))
        }
        URL_DOWNLOAD.matchEntire(url)?.let { m ->
            val (owner, repo, tag) = m.destructured
            return PluginUrlMatcher.Match("$owner/$repo", JarletToml.Plugin.Policy(pin = tag))
        }
        URL_REPO.matchEntire(url)?.let { m ->
            val (owner, repo) = m.destructured
            return PluginUrlMatcher.Match("$owner/$repo", JarletToml.Plugin.Policy())
        }
        return null
    }

    override fun process(
        serverDir: Path,
        pluginsDir: Path,
        id: String,
        policy: JarletToml.Plugin.Policy,
        trustRequested: Boolean,
    ) {
        if (!VALID_ID.matches(id)) {
            throw GithubReleasesAdapterException("Invalid GitHub owner/repo id: $id")
        }

        val releasePath = if (!policy.pin.isNullOrEmpty()) {
            "/repos/$id/releases/tags/${policy.pin}"
        } else {
            "/repos/$id/releases/latest"
        }

        val response = try {
            PluginHttp.get("$githubApi$releasePath", authHeaders())
        } catch (e: IOException) {
            throw GithubReleasesAdapterException("Could not reach GitHub for '$id'")
        }

        if (response.status == 404) {
            println("Skipping \"$id\": no matching GitHub release found (repo may not use GitHub Releases for distribution)")
            return
        }
        if (response.status !in 200..299) {
            throw GithubReleasesAdapterException("GitHub request for '$id' failed with HTTP ${response.status}")
        }

        val release = try {
            json.decodeFromString(GithubReleaseResponse.serializer(), response.body)
        } catch (e: Exception) {
            throw GithubReleasesAdapterException("Could not parse GitHub release for '$id'")
        }

        val tagName = release.tagName
        if (tagName.isNullOrEmpty()) {
            throw GithubReleasesAdapterException("GitHub returned no tag_name for '$id'")
        }

        if (release.prerelease || release.draft) {
            println("Skipping \"$id\": release $tagName is a prerelease/draft")
            return
        }

        val installed = PluginStateStore.read(serverDir, sourceName, id)?.versionName
        if (installed == tagName) {
            println("\"$id\" is already up to date ($tagName)")
            return
        }

        val (count, asset) = pickAsset(release.assets)
        if (count == 0 || asset == null) {
            println("Skipping \"$id\" $tagName: no asset in this release looks like a plugin jar; install manually")
            return
        }

        if (count > 1) {
            val pickedName = asset.stringField("name")
            val pickedTiebreakValue = asset[tiebreakField]?.jsonPrimitive?.contentOrNull
            println(
                "Multiple candidate assets found for \"$id\" $tagName; using \"$pickedName\" (highest $tiebreakField: $pickedTiebreakValue)",
            )
        }

        val assetName = asset.stringField("name")
        val assetSize = asset.longField("size")
        val assetUrl = asset.stringField("browser_download_url")
        val assetDigest = asset.stringField("digest")

        val expectedHash = assetDigest?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")

        if (assetName.isNullOrEmpty() || assetUrl.isNullOrEmpty()) {
            throw GithubReleasesAdapterException("GitHub release '$id' $tagName has an unusable asset entry")
        }

        val target = pluginsDir.resolve(assetName)
        val temporary = Files.createTempFile(pluginsDir, ".github-releases-download-", ".tmp")
        try {
            println("Downloading $id $tagName")

            val download = try {
                PluginHttp.download(assetUrl, temporary, authHeaders())
            } catch (e: IOException) {
                throw GithubReleasesAdapterException("Download failed for '$id' $tagName")
            }

            if (assetSize != null && download.size != assetSize) {
                throw GithubReleasesAdapterException("'$id' $tagName has the wrong size")
            }

            if (!expectedHash.isNullOrEmpty()) {
                val actualHash = PluginHttp.sha256Hex(temporary)
                if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                    throw GithubReleasesAdapterException("'$id' $tagName SHA-256 verification failed")
                }
            } else {
                println("Warning: no digest published for \"$id\" $tagName asset; verified by size only")
            }

            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }

        PluginStateStore.write(
            serverDir,
            InstalledVersion(
                source = sourceName,
                id = id,
                versionName = tagName,
                versionId = null,
                channelName = "",
                sha256 = expectedHash,
                size = assetSize,
                file = assetName,
                external = false,
            ),
        )

        println("Installed $id $tagName as $target")
        if (!expectedHash.isNullOrEmpty()) {
            println("SHA-256: $expectedHash")
        }
    }

    /**
     * Applies the deterministic jar-selection filter to a release's assets
     * (prefer plugin-jar-shaped content types, exclude known non-plugin
     * name patterns), then -- if more than one candidate survives -- picks
     * the one with the highest value of [tiebreakField]. Returns the
     * candidate count and the picked asset (`null` if the count is 0).
     * Mirrors `github_releases_pick_asset()`.
     */
    private fun pickAsset(assets: List<JsonObject>): Pair<Int, JsonObject?> {
        val candidates = assets.filter { asset ->
            val contentType = asset.stringField("content_type")
            val name = asset.stringField("name") ?: ""
            val isCandidateType = contentType in CANDIDATE_CONTENT_TYPES
            val isExcludedName = SOURCE_JAR.containsMatchIn(name) ||
                JAVADOC_JAR.containsMatchIn(name) ||
                SHA256_FILE.containsMatchIn(name) ||
                ASC_FILE.containsMatchIn(name) ||
                CHANGELOG_FILE.containsMatchIn(name) ||
                TXT_FILE.containsMatchIn(name)
            isCandidateType && !isExcludedName
        }

        val picked = candidates.maxByOrNull { it[tiebreakField]?.jsonPrimitive?.doubleOrNull ?: Double.NEGATIVE_INFINITY }
        return candidates.size to picked
    }

    private fun JsonObject.stringField(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.longField(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

    @Serializable
    private data class GithubReleaseResponse(
        @SerialName("tag_name") val tagName: String? = null,
        val prerelease: Boolean = false,
        val draft: Boolean = false,
        val assets: List<JsonObject> = emptyList(),
    )
}
