package me.devsnox.jarlet.config

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Covers [JarletToml.read]/[write] round-tripping for the separated server fields. */
class JarletTomlTest {

    private fun baseToml(policy: JarletToml.Policy = JarletToml.Policy()) = JarletToml(
        template = JarletToml.Template(name = "test-template", description = "A hand-crafted test template"),
        server = JarletToml.Server(
            packageInfo = JarletToml.ServerPackage("paper", "1.21.1"),
            policy = policy,
            runtime = JarletToml.ServerRuntime("2G", 25565, true),
        ),
    )

    @Test
    fun `round-trips a non-default server policy`() {
        val toml = baseToml(JarletToml.Policy(track = "channel", channel = "Release"))
        val file = Files.createTempFile("jarlet-toml-test-", ".toml")
        try {
            toml.write(file)
            val text = Files.readString(file)
            assertTrue(text.contains("[server.policy]"))
            assertTrue(text.contains("track = \"channel\""))

            val reread = JarletToml.read(file)
            assertEquals(toml.server.policy, reread.server.policy)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `omits server policy when default`() {
        val toml = baseToml()
        val file = Files.createTempFile("jarlet-toml-test-", ".toml")
        try {
            toml.write(file)
            val text = Files.readString(file)
            assertFalse(text.contains("[server.policy]"))

            val reread = JarletToml.read(file)
            assertEquals(JarletToml.Policy(), reread.server.policy)
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
