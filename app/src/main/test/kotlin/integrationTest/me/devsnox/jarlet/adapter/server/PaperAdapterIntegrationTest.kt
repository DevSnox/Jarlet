package me.devsnox.jarlet.adapter.server

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.Timeout

/**
 * Real, network-hitting smoke test for [PaperMcAdapter] -- confirms the adapter
 * can talk to the actual fill.papermc.io v3 API end to end: resolve a stable
 * build for a real Minecraft version, download its server jar, and pass the
 * SHA-256 verification inside `install()`, all without throwing.
 *
 * Deliberately not in `src/test`: that source set must never touch the
 * network (see app/build.gradle.kts). Run explicitly via
 * `./gradlew integrationTest`.
 */
class PaperMcAdapterIntegrationTest {

    /** Confirmed live against the Paper API on 2026-08-09: 1.21.11 has multiple STABLE builds (latest build 132). */
    private val minecraftVersion = "1.21.11"

    @Test
    @Timeout(120)
    fun `installs a real Paper server jar for a live stable Minecraft version`() {
        val tempDir = Files.createTempDirectory("paper-adapter-integration-test")
        try {
            val target = tempDir.resolve("server.jar")

            PaperMcAdapter.install("paper", minecraftVersion, target)

            assertTrue(target.exists(), "Expected the downloaded server jar to exist at $target")
            val actualSize = Files.size(target)
            assertTrue(
                actualSize > 1_000_000,
                "Expected a real Paper server jar (>1,000,000 bytes) but got $actualSize bytes",
            )
        } finally {
            deleteRecursively(tempDir)
        }
    }

    private fun deleteRecursively(root: Path) {
        if (!root.exists()) return
        Files.walk(root)
            .sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
    }
}
