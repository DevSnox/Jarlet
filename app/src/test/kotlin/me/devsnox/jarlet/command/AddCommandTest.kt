package me.devsnox.jarlet.command

import com.github.ajalt.clikt.testing.test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import me.devsnox.jarlet.Jarlet
import me.devsnox.jarlet.config.JarletToml

/**
 * `jarlet plugin add <name> <identifier> [--pin | --channel] [--source ...] [--trust]`
 * coverage restricted to validation paths that fail BEFORE
 * [me.devsnox.jarlet.plugin.PluginRouter.route] would ever make a real
 * network call: missing arguments, `--pin`/`--channel` mutual exclusion,
 * `--source`'s id-shape validation, an unknown `--source` value, and the
 * already-declared-id guard (only reachable network-free when `--source` is
 * given explicitly, since without it
 * [me.devsnox.jarlet.plugin.SourceResolver.resolveAddIdentifier] itself
 * probes Hangar/Spiget over the network).
 *
 * NOT covered here (needs a real network call against Hangar/Spiget/GitHub,
 * out of scope for this network-free suite): source inference from a bare
 * identifier, and the actual fetch/install once a plugin is resolved --
 * covered instead by the adapter smoke tests under `integrationTest`
 * (`HangarAdapterIntegrationTest`, `SpigetAdapterIntegrationTest`,
 * `GithubReleasesAdapterIntegrationTest`).
 */
class AddCommandTest : CommandTestSupport() {

    @Test
    fun `missing arguments are rejected`() {
        val result = Jarlet().test("plugin add")

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.isNotBlank())
    }

    @Test
    fun `adding to a non-existent server is rejected`() {
        val result = Jarlet().test(listOf("plugin", "add", "no-such-server", "EssentialsX", "--source", "hangar"))

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("No server named 'no-such-server' found"), "got: ${result.stderr}")
    }

    @Test
    fun `--pin and --channel are mutually exclusive`() {
        writeServerToml("myserver")

        val result = Jarlet().test(
            listOf("plugin", "add", "myserver", "EssentialsX", "--pin", "2.22.0", "--channel", "Release"),
        )

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("--pin and --channel are mutually exclusive"), "got: ${result.stderr}")
    }

    @Test
    fun `an unknown --source value is rejected before touching the network`() {
        writeServerToml("myserver")

        val result = Jarlet().test(listOf("plugin", "add", "myserver", "EssentialsX", "--source", "not-a-real-source"))

        assertEquals(1, result.statusCode)
        assertTrue(
            result.stderr.contains("Unknown --source 'not-a-real-source'"),
            "got: ${result.stderr}",
        )
    }

    @Test
    fun `a --source spiget id that is not numeric is rejected before touching the network`() {
        writeServerToml("myserver")

        val result = Jarlet().test(listOf("plugin", "add", "myserver", "not-numeric", "--source", "spiget"))

        assertEquals(1, result.statusCode)
        assertTrue(
            result.stderr.contains("--source spiget requires a numeric Spiget resource id"),
            "got: ${result.stderr}",
        )
    }

    @Test
    fun `a --source github-releases id that is not owner slash repo is rejected before touching the network`() {
        writeServerToml("myserver")

        val result = Jarlet().test(listOf("plugin", "add", "myserver", "not-owner-repo", "--source", "github-releases"))

        assertEquals(1, result.statusCode)
        assertTrue(
            result.stderr.contains("--source github-releases requires an 'owner/repo' id"),
            "got: ${result.stderr}",
        )
    }

    @Test
    fun `adding an id that is already declared under another source is rejected before touching the network`() {
        val declared = JarletToml.Plugin(source = "hangar", id = "EssentialsX")
        writeServerToml("myserver", defaultToml(plugins = listOf(declared)))

        val result = Jarlet().test(listOf("plugin", "add", "myserver", "EssentialsX", "--source", "hangar"))

        assertEquals(1, result.statusCode)
        assertTrue(
            result.stderr.contains("'EssentialsX' is already declared under source 'hangar'"),
            "got: ${result.stderr}",
        )
    }
}
