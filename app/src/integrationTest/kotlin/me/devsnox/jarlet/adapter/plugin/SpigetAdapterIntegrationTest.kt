package me.devsnox.jarlet.adapter.plugin

import me.devsnox.jarlet.config.JarletToml
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Live, read-only smoke test against the real Spiget API
 * (`https://api.spiget.org/v2`) -- lives in the `integrationTest` source
 * set (never `test`, which must stay network-free) precisely because it
 * makes a real HTTP call.
 *
 * Resource id 34315 is "Vault" -- confirmed live (2026-08-09) to be
 * non-external, non-premium, and to have a working `download/proxy`
 * endpoint that returns a real `Vault.jar`, so this exercises the actual
 * successful-download path end-to-end rather than a skip path.
 */
class SpigetAdapterIntegrationTest {

    @Test
    fun `downloads Vault's latest version from the real Spiget API`() {
        val serverDir = Files.createTempDirectory("spiget-it-server-")
        val pluginsDir = Files.createTempDirectory("spiget-it-plugins-")
        try {
            SpigetAdapter.process(
                serverDir = serverDir,
                pluginsDir = pluginsDir,
                id = "34315",
                policy = JarletToml.Plugin.Policy(),
                trustRequested = false,
            )

            val downloaded = Files.list(pluginsDir).use { it.toList() }
            assertTrue(downloaded.isNotEmpty(), "expected at least one file in $pluginsDir")
            assertTrue(downloaded.any { Files.size(it) > 0 }, "expected a non-empty downloaded file in $pluginsDir")
        } finally {
            serverDir.deleteRecursively()
            pluginsDir.deleteRecursively()
        }
    }
}

private fun Path.deleteRecursively() {
    if (!Files.exists(this)) return
    Files.walk(this).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
}
