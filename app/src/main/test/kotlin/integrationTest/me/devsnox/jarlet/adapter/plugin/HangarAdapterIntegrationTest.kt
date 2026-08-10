package me.devsnox.jarlet.adapter.plugin

import me.devsnox.jarlet.config.JarletToml
import org.junit.jupiter.api.Assumptions
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.streams.toList
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ONE end-to-end smoke test for [HangarAdapter] against the real, live
 * Hangar API (`https://hangar.papermc.io/api/v1`) -- confirms the adapter
 * can fetch project/version metadata and download+verify a real jar without
 * throwing. No mocking, no edge cases; that's what the (network-free) `test`
 * source set is for.
 *
 * Plugin used: `ViaVersion`, verified live via `curl` on 2026-08-09
 * (`GET /projects/ViaVersion` -> 200, `visibility: "public"`; `GET
 * /projects/ViaVersion/latest?channel=Release` -> `5.11.0`, a version whose
 * `/versions/5.11.0` metadata also came back 200). It's been this project's
 * known-good Hangar example throughout its history.
 *
 * Credentials: per [HangarAdapter]'s own doc comment, Hangar requires an
 * authenticated JWT for essentially every endpoint, minted from
 * `JARLET_HANGAR_API_KEY` (or a pre-seeded `JARLET_HANGAR_JWT`) --
 * [HangarAdapter.process] throws immediately via `authenticate()` if
 * neither is set, before it ever reaches the network. (The plain `curl`
 * probes above happened to succeed unauthenticated, but that's Hangar's
 * server-side behavior, not this adapter's -- the adapter itself still
 * gates on the env var.) Since neither is guaranteed to be present in a
 * typical dev/CI environment, this test skips gracefully via
 * `Assumptions.assumeTrue` rather than failing hard when both are absent.
 */
class HangarAdapterIntegrationTest {

    @Test
    fun `process() downloads and verifies a real ViaVersion release jar from the live Hangar API`() {
        Assumptions.assumeTrue(
            !System.getenv("JARLET_HANGAR_API_KEY").isNullOrEmpty() ||
                !System.getenv("JARLET_HANGAR_JWT").isNullOrEmpty(),
            "Skipping: neither JARLET_HANGAR_API_KEY nor JARLET_HANGAR_JWT is set in this environment",
        )

        val serverDir = Files.createTempDirectory("jarlet-hangar-it-server-")
        val pluginsDir = Files.createTempDirectory("jarlet-hangar-it-plugins-")
        try {
            HangarAdapter.process(
                serverDir,
                pluginsDir,
                "ViaVersion",
                JarletToml.Policy(track = "channel", channel = "Release"),
                trustRequested = false,
            )

            val downloaded = Files.list(pluginsDir).use { it.toList() }
            assertTrue(downloaded.isNotEmpty(), "Expected at least one downloaded file in $pluginsDir")
            assertTrue(
                downloaded.any { Files.size(it) > 0 },
                "Expected at least one non-empty downloaded file in $pluginsDir",
            )
        } finally {
            deleteRecursively(serverDir)
            deleteRecursively(pluginsDir)
        }
    }

    private fun deleteRecursively(root: Path) {
        if (!root.exists()) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path ->
                if (path.isDirectory() || Files.exists(path)) Files.deleteIfExists(path)
            }
        }
    }
}
