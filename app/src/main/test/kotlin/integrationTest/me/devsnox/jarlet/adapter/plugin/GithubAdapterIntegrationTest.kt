package me.devsnox.jarlet.adapter.plugin

import me.devsnox.jarlet.config.JarletToml
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Live smoke test for [GithubAdapter] -- makes a real, read-only call
 * against `https://api.github.com` and downloads a real release asset. Runs
 * in the separate `integrationTest` source set (never `test`, which must
 * stay network-free); run it explicitly, e.g. `./gradlew integrationTest`.
 *
 * Pinned to ViaVersion/ViaVersion's `5.11.0` tag: as of writing, that
 * release has exactly one asset (`ViaVersion-5.11.0.jar`,
 * `application/java-archive`, with a published `sha256:` digest), so
 * [GithubAdapter.pickAsset]'s filter/tie-break heuristic resolves to
 * a single unambiguous candidate -- a true end-to-end happy path, not the
 * multi-candidate or no-candidate edge cases.
 *
 * Unauthenticated GitHub API calls are capped at 60 req/hr; this test makes
 * exactly one. Setting `JARLET_GITHUB_TOKEN` raises that limit but is
 * entirely optional -- the test works fine without it.
 */
class GithubAdapterIntegrationTest {

    @Test
    fun `download ViaVersion from GitHub`() {
        val serverDir = Files.createTempDirectory("jarlet-github-releases-it-server-")
        val pluginsDir = Files.createTempDirectory("jarlet-github-releases-it-plugins-")
        try {
            GithubAdapter.process(
                serverDir = serverDir,
                pluginsDir = pluginsDir,
                id = "ViaVersion/ViaVersion",
                policy = JarletToml.Plugin.Policy(pin = "5.11.0"),
                trustRequested = false,
            )

            val downloaded = Files.list(pluginsDir).use { it.toList() }
            assertTrue(downloaded.isNotEmpty(), "Expected at least one file to be downloaded into $pluginsDir")
            assertTrue(
                downloaded.any { Files.size(it) > 0 },
                "Expected at least one downloaded file with size > 0 bytes",
            )
        } finally {
            deleteRecursively(serverDir)
            deleteRecursively(pluginsDir)
        }
    }

    private fun deleteRecursively(root: Path) {
        if (!root.exists()) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
