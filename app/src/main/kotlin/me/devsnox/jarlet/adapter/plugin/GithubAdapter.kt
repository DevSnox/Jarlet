package me.devsnox.jarlet.adapter.plugin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.devsnox.jarlet.Log
import me.devsnox.jarlet.config.JarletToml
import me.devsnox.jarlet.config.SysConfig
import me.devsnox.jarlet.config.InstalledVersion
import me.devsnox.jarlet.lib.SemVer
import me.devsnox.jarlet.lib.SharedHttp
import me.devsnox.jarlet.lib.pickHighestWithinBound
import me.devsnox.jarlet.plugin.PluginSourceAdapter
import me.devsnox.jarlet.config.PluginStateStore
import me.devsnox.jarlet.plugin.PluginUrlMatcher
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Thrown for GitHub Releases request/parse/verification failures. */
class GithubAdapterException(message: String) : Exception(message)

/**
 * GitHub Releases plugin source adapter.
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
 * (`GITHUB_ASSET_TIEBREAK_FIELD`) and looked up dynamically, not hardcoded
 * to `download_count`.
 */
object GithubAdapter : PluginSourceAdapter, PluginUrlMatcher {
    override val sourceName: String = "github"
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
    private val releasesListPerPage: Int by lazy { SysConfig.default().value("GITHUB_RELEASES_LIST_PER_PAGE").toInt() }
    private val releasesListMaxPages: Int by lazy { SysConfig.default().value("GITHUB_RELEASES_LIST_MAX_PAGES").toInt() }
    private val envFilePath: String by lazy { SysConfig.default().value("ENV_FILE_PATH") }

    /**
     * The `repo` portion of an `owner/repo` [id] (e.g. `"Essentials"` for
     * `"EssentialsX/Essentials"`) -- cached as `InstalledVersion.displayName`
     * so `jarlet plugin list` can show it and
     * [me.devsnox.jarlet.plugin.SourceResolver.resolveDeclaredIdentifier]
     * can accept it as a `remove`/`update` shorthand. Internal (not
     * private) only so [GithubAdapterTest] can assert on it directly
     * without needing a live/mocked GitHub API round-trip.
     */
    internal fun repoNameOf(id: String): String = id.substringAfterLast('/')

    /** `JARLET_GITHUB_TOKEN`, if set -- raises the unauthenticated 60 req/hr limit to 5000 req/hr when supplied. Never written to disk, process-scoped only, same treatment [HangarAdapter] gives its JWT. */
    private fun authHeaders(): Map<String, String> {
        val token = System.getenv("JARLET_GITHUB_TOKEN")
        val headers = mutableMapOf("Accept" to "application/vnd.github+json")
        if (!token.isNullOrEmpty()) headers["Authorization"] = "Bearer $token"
        return headers
    }

    /**
     * Recognizes GitHub repo/release URLs (with or without a trailing
     * slash, `http://`/`https://`; `www.github.com` is never used by
     * GitHub itself, so not matched):
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
            return PluginUrlMatcher.Match("$owner/$repo", JarletToml.Policy(pin = tag))
        }
        URL_DOWNLOAD.matchEntire(url)?.let { m ->
            val (owner, repo, tag) = m.destructured
            return PluginUrlMatcher.Match("$owner/$repo", JarletToml.Policy(pin = tag))
        }
        URL_REPO.matchEntire(url)?.let { m ->
            val (owner, repo) = m.destructured
            return PluginUrlMatcher.Match("$owner/$repo", JarletToml.Policy())
        }
        return null
    }

    override fun process(
        serverDir: Path,
        pluginsDir: Path,
        id: String,
        policy: JarletToml.Policy,
        trustRequested: Boolean,
    ) {
        if (!VALID_ID.matches(id)) {
            throw GithubAdapterException("Invalid GitHub owner/repo id: $id")
        }

        // Minor/patch tracking needs the highest release *within the
        // installed baseline's bound*, which isn't necessarily
        // /releases/latest -- so it requires a separate paginated list
        // fetch. Gated tightly behind pin being absent and track being
        // exactly "minor"/"patch" so every other policy shape (pin,
        // track=latest, no policy) takes the exact same single-fetch path
        // as before, with zero added requests.
        val useTrackBound = policy.pin.isNullOrEmpty() && (policy.track == "minor" || policy.track == "patch")
        val baseline = if (useTrackBound) {
            PluginStateStore.read(serverDir, sourceName, id)?.versionName?.let { SemVer.parse(it) }
        } else {
            null
        }

        val release: GithubReleaseResponse
        if (useTrackBound && baseline != null) {
            val picked = fetchReleaseWithinBound(id, baseline, policy.track!!)
            if (picked == null) {
                Log.info("No $id release within the track=${policy.track} bound of the installed version was found")
                return
            }
            release = picked
        } else {
            if (useTrackBound) {
                Log.debug("no semver baseline installed for \"$id\" yet; falling back to latest for track=${policy.track}")
            }

            val releasePath = if (!policy.pin.isNullOrEmpty()) {
                "/repos/$id/releases/tags/${policy.pin}"
            } else {
                "/repos/$id/releases/latest"
            }

            val response = try {
                SharedHttp.get("$githubApi$releasePath", authHeaders())
            } catch (e: IOException) {
                throw GithubAdapterException("Could not reach GitHub for '$id'")
            }

            if (response.status == 404) {
                Log.info("Skipping \"$id\": no matching GitHub release found (repo may not use GitHub Releases for distribution)")
                return
            }
            if (response.status !in 200..299) {
                if (isRateLimited(response)) {
                    throw GithubAdapterException(rateLimitMessage(response))
                }
                throw GithubAdapterException("GitHub request for '$id' failed with HTTP ${response.status}")
            }

            release = try {
                json.decodeFromString(GithubReleaseResponse.serializer(), response.body)
            } catch (e: Exception) {
                throw GithubAdapterException("Could not parse GitHub release for '$id'")
            }
        }

        val tagName = release.tagName
        if (tagName.isNullOrEmpty()) {
            throw GithubAdapterException("GitHub returned no tag_name for '$id'")
        }

        if (release.prerelease || release.draft) {
            Log.info("Skipping \"$id\": release $tagName is a prerelease/draft")
            return
        }

        val installed = PluginStateStore.read(serverDir, sourceName, id)?.versionName
        if (installed == tagName) {
            Log.info("\"$id\" is already up to date ($tagName)")
            return
        }

        val (count, asset) = pickAsset(release.assets)
        if (count == 0 || asset == null) {
            Log.info("Skipping \"$id\" $tagName: no asset in this release looks like a plugin jar; install manually")
            return
        }

        if (count > 1) {
            val pickedName = asset.stringField("name")
            val pickedTiebreakValue = asset[tiebreakField]?.jsonPrimitive?.contentOrNull
            Log.info(
                "Multiple candidate assets found for \"$id\" $tagName; using \"$pickedName\" (highest $tiebreakField: $pickedTiebreakValue)",
            )
        }

        val assetName = asset.stringField("name")
        val assetSize = asset.longField("size")
        val assetUrl = asset.stringField("browser_download_url")
        val assetDigest = asset.stringField("digest")

        val expectedHash = assetDigest?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")

        if (assetName.isNullOrEmpty() || assetUrl.isNullOrEmpty()) {
            throw GithubAdapterException("GitHub release '$id' $tagName has an unusable asset entry")
        }

        val target = pluginsDir.resolve(assetName)
        val temporary = Files.createTempFile(pluginsDir, ".github-download-", ".tmp")
        try {
            Log.info("Downloading $id $tagName")

            val download = try {
                SharedHttp.download(assetUrl, temporary, authHeaders())
            } catch (e: IOException) {
                throw GithubAdapterException("Download failed for '$id' $tagName")
            }

            if (assetSize != null && download.size != assetSize) {
                throw GithubAdapterException("'$id' $tagName has the wrong size")
            }

            if (!expectedHash.isNullOrEmpty()) {
                val actualHash = SharedHttp.sha256Hex(temporary)
                if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                    throw GithubAdapterException("'$id' $tagName SHA-256 verification failed")
                }
            } else {
                Log.info("Warning: no digest published for \"$id\" $tagName asset; verified by size only")
            }

            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }

        // The new release's asset filename may differ from what was
        // previously recorded (e.g. an embedded version number bump) --
        // remove the now-stale jar only now that the replacement is
        // verified and on disk. Must run before write() overwrites the old
        // record.
        PluginStateStore.deleteStaleFile(serverDir, pluginsDir, sourceName, id, assetName)

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
                // Cached so `jarlet plugin list` (and declared-identifier
                // resolution, see SourceResolver.resolveDeclaredIdentifier)
                // can show/accept the repo name alone instead of the full
                // "owner/repo" id -- unlike Spiget this needs no extra API
                // call, since [id] already contains it.
                displayName = repoNameOf(id),
            ),
        )

        Log.info("Installed $id $tagName as $target")
        if (!expectedHash.isNullOrEmpty()) {
            Log.info("SHA-256: $expectedHash")
        }
    }

    /**
     * Pages through `GET /repos/$id/releases` (bounded by
     * `GITHUB_RELEASES_LIST_MAX_PAGES` pages of
     * `GITHUB_RELEASES_LIST_PER_PAGE` each, per `jarlet-sys.conf`),
     * filtering out prerelease/draft entries (the same rule [process]
     * already applies to the single-release paths just below), and picks
     * the highest release within [track]'s bound of [baseline] via
     * [pickHighestWithinBound]. Returns null if none qualify. A page
     * returning fewer than the configured page size ends the scan early --
     * same idiom [SpigetAdapter.resolvePinnedVersion] already uses. Only
     * reached when [process] already established a usable semver baseline
     * for a `track = "minor"/"patch"` policy -- see there.
     */
    private fun fetchReleaseWithinBound(id: String, baseline: SemVer, track: String): GithubReleaseResponse? {
        val candidates = mutableListOf<GithubReleaseResponse>()

        for (page in 1..releasesListMaxPages) {
            val response = try {
                SharedHttp.get("$githubApi/repos/$id/releases?per_page=$releasesListPerPage&page=$page", authHeaders())
            } catch (e: IOException) {
                throw GithubAdapterException("Could not reach GitHub while listing releases for '$id'")
            }

            if (response.status !in 200..299) {
                if (isRateLimited(response)) {
                    throw GithubAdapterException(rateLimitMessage(response))
                }
                throw GithubAdapterException("GitHub release list request for '$id' failed with HTTP ${response.status}")
            }

            val pageReleases = try {
                json.decodeFromString(ListSerializer(GithubReleaseResponse.serializer()), response.body)
            } catch (e: Exception) {
                throw GithubAdapterException("Could not parse GitHub release list for '$id'")
            }

            candidates += pageReleases.filterNot { it.prerelease || it.draft }

            // Fewer than a full page means we've reached the end of the list.
            if (pageReleases.size < releasesListPerPage) break
        }

        return pickHighestWithinBound(candidates, { it.tagName ?: "" }, baseline, track)
    }

    /**
     * Applies the deterministic jar-selection filter to a release's assets
     * (prefer plugin-jar-shaped content types, exclude known non-plugin
     * name patterns), then -- if more than one candidate survives -- picks
     * the one with the highest value of [tiebreakField]. Returns the
     * candidate count and the picked asset (`null` if the count is 0).
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

    /**
     * True when [response] looks like GitHub's documented rate-limit
     * rejection shape rather than a genuine access-denial/other failure:
     * `403` (occasionally `429`) with either `x-ratelimit-remaining: 0` or
     * a JSON body whose `message` field contains `"API rate limit exceeded"`.
     * Both signals are checked rather than relying on either alone.
     */
    private fun isRateLimited(response: SharedHttp.Response): Boolean {
        if (response.status != 403 && response.status != 429) return false
        if (response.headers["x-ratelimit-remaining"] == "0") return true
        val message = try {
            json.parseToJsonElement(response.body).jsonObject["message"]?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            null
        }
        return message?.contains("API rate limit exceeded", ignoreCase = true) == true
    }

    /**
     * Builds the actionable rate-limit error message: mentions
     * `JARLET_GITHUB_TOKEN` only if it isn't already set (telling a user who
     * already set one to "set one" would be actively confusing), and
     * includes a human-readable reset time when `x-ratelimit-reset` (Unix
     * epoch seconds) is present.
     */
    private fun rateLimitMessage(response: SharedHttp.Response): String {
        val hasToken = !System.getenv("JARLET_GITHUB_TOKEN").isNullOrEmpty()
        val resetSuffix = response.headers["x-ratelimit-reset"]?.toLongOrNull()?.let { epochSeconds ->
            " (resets at ${java.time.Instant.ofEpochSecond(epochSeconds)})"
        } ?: ""
        val advice = if (hasToken) {
            "JARLET_GITHUB_TOKEN is already set but GitHub still rejected this request as rate-limited"
        } else {
            "no JARLET_GITHUB_TOKEN set -- set one (permanently in $envFilePath, or export it) to raise the limit substantially"
        }
        return "GitHub rate limit reached$resetSuffix ($advice)"
    }

    @Serializable
    private data class GithubReleaseResponse(
        @SerialName("tag_name") val tagName: String? = null,
        val prerelease: Boolean = false,
        val draft: Boolean = false,
        val assets: List<JsonObject> = emptyList(),
    )
}
