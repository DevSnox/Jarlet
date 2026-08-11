package me.devsnox.jarlet.command

import com.github.ajalt.clikt.testing.test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import me.devsnox.jarlet.Jarlet
import me.devsnox.jarlet.config.JarletToml

/**
 * `jarlet plugin track <name> <identifier> [--pin <version> | --channel <name> | --track minor|patch]`
 * coverage -- entirely network-free (a declared plugin's policy retarget
 * is pure local bookkeeping: a `jarlet.toml` rewrite, per
 * [PluginTrackCommand]'s doc comment). Covers the mutual-exclusivity/
 * validity checks, the unknown-identifier error, case-insensitive id
 * resolution, and that only the targeted plugin's policy changes.
 */
class PluginTrackCommandTest : CommandTestSupport() {

    @Test
    fun `missing name argument is rejected`() {
        val result = Jarlet().test("plugin track")

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.isNotBlank())
    }

    @Test
    fun `tracking an unknown identifier is rejected`() {
        val declared = JarletToml.Plugin(source = "hangar", id = "EssentialsX")
        writeServerToml("myserver", defaultToml(plugins = listOf(declared)))

        val result = Jarlet().test(listOf("plugin", "track", "myserver", "NotDeclared", "--pin", "1.0.0"))

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("No declared plugin with id 'NotDeclared'"), "got: ${result.stderr}")
    }

    @Test
    fun `no policy option given is rejected`() {
        val declared = JarletToml.Plugin(source = "hangar", id = "EssentialsX")
        writeServerToml("myserver", defaultToml(plugins = listOf(declared)))

        val result = Jarlet().test(listOf("plugin", "track", "myserver", "EssentialsX"))

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("Exactly one of --pin, --channel, or --track is required"), "got: ${result.stderr}")
    }

    @Test
    fun `an invalid --track value is rejected`() {
        val declared = JarletToml.Plugin(source = "hangar", id = "EssentialsX")
        writeServerToml("myserver", defaultToml(plugins = listOf(declared)))

        val result = Jarlet().test(listOf("plugin", "track", "myserver", "EssentialsX", "--track", "foo"))

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("""--track must be "minor" or "patch""""), "got: ${result.stderr}")
    }

    @Test
    fun `--pin sets that plugin's policy to pin, leaving other declared plugins untouched`() {
        val pluginA = JarletToml.Plugin(source = "hangar", id = "PluginA")
        val pluginB = JarletToml.Plugin(source = "hangar", id = "PluginB")
        val tomlFile = writeServerToml("myserver", defaultToml(plugins = listOf(pluginA, pluginB)))

        val result = Jarlet().test(listOf("plugin", "track", "myserver", "PluginA", "--pin", "2.0.0"))

        assertEquals(0, result.statusCode)
        val rewritten = JarletToml.read(tomlFile)
        assertEquals(JarletToml.Policy(pin = "2.0.0"), rewritten.plugins.first { it.id == "PluginA" }.policy)
        assertEquals(JarletToml.Policy(), rewritten.plugins.first { it.id == "PluginB" }.policy)
    }

    @Test
    fun `--track patch sets that plugin's policy to track patch`() {
        val declared = JarletToml.Plugin(source = "hangar", id = "EssentialsX")
        val tomlFile = writeServerToml("myserver", defaultToml(plugins = listOf(declared)))

        val result = Jarlet().test(listOf("plugin", "track", "myserver", "EssentialsX", "--track", "patch"))

        assertEquals(0, result.statusCode)
        val rewritten = JarletToml.read(tomlFile)
        assertEquals(JarletToml.Policy(track = "patch"), rewritten.plugins.first { it.id == "EssentialsX" }.policy)
    }

    @Test
    fun `identifier resolution is case-insensitive`() {
        val declared = JarletToml.Plugin(source = "hangar", id = "SomePlugin")
        val tomlFile = writeServerToml("myserver", defaultToml(plugins = listOf(declared)))

        val result = Jarlet().test(listOf("plugin", "track", "myserver", "someplugin", "--channel", "Beta"))

        assertEquals(0, result.statusCode)
        val rewritten = JarletToml.read(tomlFile)
        assertEquals(
            JarletToml.Policy(track = "channel", channel = "Beta"),
            rewritten.plugins.first { it.id == "SomePlugin" }.policy,
        )
    }
}
