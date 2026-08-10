package me.devsnox.jarlet.config

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Covers [JarletToml.read]/[write] round-tripping for the `[server].policy` field specifically. */
class JarletTomlTest {

    private fun baseToml(policy: JarletToml.Policy = JarletToml.Policy()) = JarletToml(
        template = JarletToml.Template(name = "test-template", description = "A hand-crafted test template"),
        server = JarletToml.Server(
            pkg = "paper",
            minecraftVersion = "1.21.1",
            memory = "2G",
            port = 25565,
            onlineMode = true,
            policy = policy,
        ),
    )

    @Test
    fun `round-trips a non-default server policy`() {
        val toml = baseToml(JarletToml.Policy(track = "channel", channel = "Release"))
        val file = Files.createTempFile("jarlet-toml-test-", ".toml")
        try {
            toml.write(file)
            val text = Files.readString(file)
            assertTrue(text.contains("policy = { track = \"channel\", channel = \"Release\" }"))

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
            assertFalse(text.contains("policy"))

            val reread = JarletToml.read(file)
            assertEquals(JarletToml.Policy(), reread.server.policy)
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
