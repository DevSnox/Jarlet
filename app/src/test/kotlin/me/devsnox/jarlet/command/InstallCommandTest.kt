package me.devsnox.jarlet.command

import com.github.ajalt.clikt.testing.test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import me.devsnox.jarlet.Jarlet

/**
 * `jarlet install <minecraft-version> [target] [package]` coverage
 * restricted to the one path that never touches the network:
 * [me.devsnox.jarlet.adapter.server.ServerSoftwareAdapters.find] rejects an
 * unknown `package` before [me.devsnox.jarlet.adapter.server.ServerSoftwareAdapter.install]
 * ever runs. The success path (paper) requires a real download from
 * `PAPER_API` and is out of scope here -- see the existing
 * `PaperAdapterIntegrationTest` under `integrationTest` for that coverage.
 */
class InstallCommandTest : CommandTestSupport() {

    @Test
    fun `missing minecraft-version argument is rejected`() {
        val result = Jarlet().test("install")

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("minecraft-version", ignoreCase = true), "got: ${result.stderr}")
    }

    @Test
    fun `an unknown server package is rejected before any download is attempted`() {
        val result = Jarlet().test(listOf("install", "1.21.1", "server.jar", "not-a-real-package"))

        assertEquals(1, result.statusCode)
        assertTrue(
            result.stderr.contains("Unknown [server].package 'not-a-real-package'"),
            "got: ${result.stderr}",
        )
    }
}
