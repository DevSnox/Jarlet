package me.devsnox.jarlet.command

import com.github.ajalt.clikt.testing.test
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import me.devsnox.jarlet.Jarlet
import me.devsnox.jarlet.config.JarletToml
import me.devsnox.jarlet.plugin.InstalledVersion
import me.devsnox.jarlet.plugin.PluginStateStore

/**
 * `jarlet plugin remove <name> <identifier>` coverage -- entirely
 * network-free by construction (removal is pure local bookkeeping: a
 * `jarlet.toml` rewrite, an installed-jar delete, and a
 * `plugins-state.json` entry removal, per [me.devsnox.jarlet.command.RemoveCommand]'s
 * doc comment). Covers the missing-identifier error, the case-insensitive
 * id-matching fix, and the full uninstall (toml + jar + state, in that
 * order).
 */
class RemoveCommandTest : CommandTestSupport() {

    @Test
    fun `missing arguments are rejected`() {
        val result = Jarlet().test("plugin remove")

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.isNotBlank())
    }

    @Test
    fun `removing from a non-existent server is rejected`() {
        val result = Jarlet().test(listOf("plugin", "remove", "no-such-server", "EssentialsX"))

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("No server named 'no-such-server' found"), "got: ${result.stderr}")
    }

    @Test
    fun `removing an id that is not declared is rejected`() {
        writeServerToml("myserver")

        val result = Jarlet().test(listOf("plugin", "remove", "myserver", "NotDeclared"))

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("No declared plugin with id 'NotDeclared'"), "got: ${result.stderr}")
    }

    @Test
    fun `removing an installed plugin drops it from jarlet toml, deletes its jar, and clears its state entry`() {
        val declared = JarletToml.Plugin(
            source = "hangar",
            id = "EssentialsX",
            policy = JarletToml.Plugin.Policy(pin = "2.22.0"),
        )
        val tomlFile = writeServerToml("myserver", defaultToml(plugins = listOf(declared)))
        val serverDir = tomlFile.parent
        val pluginsDir = Files.createDirectories(serverDir.resolve("plugins"))
        val jarFile = pluginsDir.resolve("EssentialsX.jar")
        Files.writeString(jarFile, "fake jar bytes")
        PluginStateStore.write(
            serverDir,
            InstalledVersion(source = "hangar", id = "EssentialsX", versionName = "2.22.0", file = "EssentialsX.jar"),
        )

        val result = Jarlet().test(listOf("plugin", "remove", "myserver", "EssentialsX"))

        assertEquals(0, result.statusCode)
        assertTrue(result.stdout.contains("""Removed "EssentialsX" (hangar)"""), "got: ${result.stdout}")

        val rewritten = JarletToml.read(tomlFile)
        assertTrue(rewritten.plugins.none { it.id == "EssentialsX" }, "expected EssentialsX to be dropped from jarlet.toml")

        assertFalse(Files.exists(jarFile), "expected the installed jar to be deleted")
        assertTrue(PluginStateStore.read(serverDir, "hangar", "EssentialsX") == null, "expected the state entry to be cleared")
    }

    @Test
    fun `removing matches a declared id case-insensitively`() {
        val declared = JarletToml.Plugin(source = "hangar", id = "Geyser")
        val tomlFile = writeServerToml("myserver", defaultToml(plugins = listOf(declared)))

        val result = Jarlet().test(listOf("plugin", "remove", "myserver", "geyser"))

        assertEquals(0, result.statusCode)
        assertTrue(result.stdout.contains("""Removed "Geyser" (hangar)"""), "got: ${result.stdout}")

        val rewritten = JarletToml.read(tomlFile)
        assertTrue(rewritten.plugins.isEmpty(), "expected the case-insensitively-matched entry to be removed")
    }

    @Test
    fun `removing a declared plugin with no recorded installed jar still drops the declaration cleanly`() {
        val declared = JarletToml.Plugin(source = "spiget", id = "12345")
        val tomlFile = writeServerToml("myserver", defaultToml(plugins = listOf(declared)))

        val result = Jarlet().test(listOf("plugin", "remove", "myserver", "12345"))

        assertEquals(0, result.statusCode)
        val rewritten = JarletToml.read(tomlFile)
        assertTrue(rewritten.plugins.isEmpty())
    }
}
