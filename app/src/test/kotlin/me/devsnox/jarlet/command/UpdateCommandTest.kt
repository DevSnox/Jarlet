package me.devsnox.jarlet.command

import com.github.ajalt.clikt.testing.test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import me.devsnox.jarlet.Jarlet
import me.devsnox.jarlet.config.JarletToml

/**
 * `jarlet plugin update <name> [<identifier>] [--trust]` coverage
 * restricted to paths that never reach a real source adapter (and
 * therefore never touch the network):
 *
 * - No declared plugins at all ([me.devsnox.jarlet.plugin.PluginRouter.routeAll]'s "No plugins declared" branch).
 * - A plugin declared under a source with no registered adapter --
 *   [me.devsnox.jarlet.plugin.AdapterRegistry.find] returns `null`, so
 *   [me.devsnox.jarlet.plugin.PluginRouter.route] prints a "Skipping"
 *   message and returns without ever reaching a real adapter's `process()`.
 *   This also exercises [me.devsnox.jarlet.plugin.PluginDependencyChecker.checkAndResolve]
 *   network-free: it no-ops immediately since nothing was actually
 *   installed for a skipped entry.
 * - `update <identifier>` for an id that isn't declared at all
 *   ([me.devsnox.jarlet.plugin.PluginRouter.routeOne]'s lookup fails before
 *   any routing happens).
 *
 * NOT covered here (needs a real network call against Hangar/Spiget/GitHub,
 * out of scope for this network-free suite): actually fetching/updating a
 * plugin declared under `hangar`/`spiget`/`github` -- covered
 * instead by the adapter smoke tests under `integrationTest`.
 */
class UpdateCommandTest : CommandTestSupport() {

    @Test
    fun `missing name argument is rejected`() {
        val result = Jarlet().test("plugin update")

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("name", ignoreCase = true), "got: ${result.stderr}")
    }

    @Test
    fun `updating a non-existent server is rejected`() {
        val result = Jarlet().test(listOf("plugin", "update", "no-such-server", "*"))

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("No server named 'no-such-server' found"), "got: ${result.stderr}")
    }

    @Test
    fun `updating all with no declared plugins prints No plugins declared`() {
        writeServerToml("myserver")

        val result = Jarlet().test(listOf("plugin", "update", "myserver", "*"))

        assertEquals(0, result.statusCode)
        assertTrue(result.stdout.contains("No plugins declared"), "got: ${result.stdout}")
    }

    @Test
    fun `updating all skips a declared plugin whose source has no registered adapter`() {
        val declared = JarletToml.Plugin(source = "not-a-real-source", id = "SomePlugin")
        writeServerToml("myserver", defaultToml(plugins = listOf(declared)))

        val result = Jarlet().test(listOf("plugin", "update", "myserver", "*"))

        assertEquals(0, result.statusCode)
        assertTrue(
            result.stdout.contains("""Skipping "SomePlugin" (not-a-real-source): no adapter is implemented"""),
            "got: ${result.stdout}",
        )
    }

    @Test
    fun `updating with no identifier is rejected`() {
        writeServerToml("myserver")

        val result = Jarlet().test(listOf("plugin", "update", "myserver"))

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("identifier", ignoreCase = true), "got: ${result.stderr}")
    }

    @Test
    fun `updating one unknown identifier is rejected`() {
        writeServerToml("myserver", defaultToml(plugins = listOf(JarletToml.Plugin(source = "hangar", id = "EssentialsX"))))

        val result = Jarlet().test(listOf("plugin", "update", "myserver", "NotDeclared"))

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("No declared plugin with id 'NotDeclared'"), "got: ${result.stderr}")
    }

    @Test
    fun `updating one declared plugin whose source has no registered adapter is skipped, not an error`() {
        val declared = JarletToml.Plugin(source = "not-a-real-source", id = "SomePlugin")
        writeServerToml("myserver", defaultToml(plugins = listOf(declared)))

        val result = Jarlet().test(listOf("plugin", "update", "myserver", "SomePlugin"))

        assertEquals(0, result.statusCode)
        assertTrue(
            result.stdout.contains("""Skipping "SomePlugin" (not-a-real-source): no adapter is implemented"""),
            "got: ${result.stdout}",
        )
    }

    @Test
    fun `updating one matches a declared id case-insensitively`() {
        val declared = JarletToml.Plugin(source = "not-a-real-source", id = "SomePlugin")
        writeServerToml("myserver", defaultToml(plugins = listOf(declared)))

        val result = Jarlet().test(listOf("plugin", "update", "myserver", "someplugin"))

        assertEquals(0, result.statusCode)
        assertTrue(
            result.stdout.contains("""Skipping "SomePlugin" (not-a-real-source)"""),
            "got: ${result.stdout}",
        )
    }
}
