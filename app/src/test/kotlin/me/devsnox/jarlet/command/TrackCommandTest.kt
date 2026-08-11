package me.devsnox.jarlet.command

import com.github.ajalt.clikt.testing.test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import me.devsnox.jarlet.Jarlet
import me.devsnox.jarlet.config.JarletToml

/**
 * `jarlet track <name> [--pin <version> | --channel <name> | --track minor|patch]`
 * coverage -- entirely network-free (a server-policy retarget is pure
 * local bookkeeping: a `jarlet.toml` rewrite, per [TrackCommand]'s doc
 * comment). Covers the mutual-exclusivity/validity checks and each of the
 * three policy shapes actually landing in the rewritten `[server].policy`.
 */
class TrackCommandTest : CommandTestSupport() {

    @Test
    fun `missing name argument is rejected`() {
        val result = Jarlet().test("track")

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.isNotBlank())
    }

    @Test
    fun `tracking a non-existent server is rejected`() {
        val result = Jarlet().test(listOf("track", "no-such-server", "--pin", "1.2.3"))

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("No server named"), "got: ${result.stderr}")
    }

    @Test
    fun `no policy option given is rejected`() {
        writeServerToml("myserver")

        val result = Jarlet().test(listOf("track", "myserver"))

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("Exactly one of --pin, --channel, or --track is required"), "got: ${result.stderr}")
    }

    @Test
    fun `giving both --pin and --channel is rejected`() {
        writeServerToml("myserver")

        val result = Jarlet().test(listOf("track", "myserver", "--pin", "1.2.3", "--channel", "Beta"))

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("Exactly one of --pin, --channel, or --track is required"), "got: ${result.stderr}")
    }

    @Test
    fun `an invalid --track value is rejected`() {
        writeServerToml("myserver")

        val result = Jarlet().test(listOf("track", "myserver", "--track", "foo"))

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("""--track must be "minor" or "patch""""), "got: ${result.stderr}")
    }

    @Test
    fun `--pin sets the server policy to pin`() {
        val tomlFile = writeServerToml("myserver")

        val result = Jarlet().test(listOf("track", "myserver", "--pin", "1.2.3"))

        assertEquals(0, result.statusCode)
        val rewritten = JarletToml.read(tomlFile)
        assertEquals(JarletToml.Policy(pin = "1.2.3"), rewritten.server.policy)
    }

    @Test
    fun `--channel sets the server policy to track that channel`() {
        val tomlFile = writeServerToml("myserver")

        val result = Jarlet().test(listOf("track", "myserver", "--channel", "Beta"))

        assertEquals(0, result.statusCode)
        val rewritten = JarletToml.read(tomlFile)
        assertEquals(JarletToml.Policy(track = "channel", channel = "Beta"), rewritten.server.policy)
    }

    @Test
    fun `--track minor sets the server policy to track minor`() {
        val tomlFile = writeServerToml("myserver")

        val result = Jarlet().test(listOf("track", "myserver", "--track", "minor"))

        assertEquals(0, result.statusCode)
        val rewritten = JarletToml.read(tomlFile)
        assertEquals(JarletToml.Policy(track = "minor"), rewritten.server.policy)
    }
}
