package me.devsnox.jarlet.command

import com.github.ajalt.clikt.testing.test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import me.devsnox.jarlet.Jarlet
import me.devsnox.jarlet.config.JarletToml
import me.devsnox.jarlet.plugin.InstalledVersion
import me.devsnox.jarlet.plugin.PluginStateStore

/**
 * `jarlet plugin list <name> [--page <n> | --all]` coverage -- entirely
 * network-free by construction (list.sh's own logic only ever reads a
 * hand-crafted `jarlet.toml` + `plugins-state.json`, never a source
 * adapter). Covers: no server / no toml, an empty declared list,
 * pagination (page bounds, `--page`, `--all`), the merge of DECLARED vs
 * INSTALLED state (including case-insensitive id matching), and the
 * Spiget display-name caching behavior.
 */
class ListCommandTest : CommandTestSupport() {

    @Test
    fun `missing name argument is rejected`() {
        val result = Jarlet().test("plugin list")

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("name", ignoreCase = true), "got: ${result.stderr}")
    }

    @Test
    fun `listing plugins for a non-existent server is rejected`() {
        val result = Jarlet().test(listOf("plugin", "list", "no-such-server"))

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("No server named 'no-such-server' found"), "got: ${result.stderr}")
    }

    @Test
    fun `listing plugins for a server with no jarlet toml is rejected`() {
        createServerDir("myserver")

        val result = Jarlet().test(listOf("plugin", "list", "myserver"))

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("does not exist"), "got: ${result.stderr}")
        assertTrue(result.stderr.contains("Run setup"), "got: ${result.stderr}")
    }

    @Test
    fun `an empty declared plugins list prints No plugins declared`() {
        writeServerToml("myserver")

        val result = Jarlet().test(listOf("plugin", "list", "myserver"))

        assertEquals(0, result.statusCode)
        assertTrue(result.stdout.contains("No plugins declared"), "got: ${result.stdout}")
    }

    @Test
    fun `a negative or zero page number is rejected`() {
        writeServerToml("myserver")

        val result = Jarlet().test(listOf("plugin", "list", "myserver", "--page", "0"))

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("--page must be a positive integer"), "got: ${result.stderr}")
    }

    @Test
    fun `requesting a page past the end is rejected`() {
        writeServerToml("myserver", defaultToml(plugins = listOf(declaredPlugin("hangar", "EssentialsX"))))

        val result = Jarlet().test(listOf("plugin", "list", "myserver", "--page", "5"))

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("Page 5 does not exist"), "got: ${result.stderr}")
    }

    @Test
    fun `lists a mix of installed and not-installed plugins with their declared policy`() {
        val tomlFile = writeServerToml(
            "myserver",
            defaultToml(
                plugins = listOf(
                    declaredPlugin("hangar", "EssentialsX", pin = "2.22.0"),
                    declaredPlugin("github", "owner/repo", channel = "Release"),
                ),
            ),
        )
        val serverDir = tomlFile.parent
        PluginStateStore.write(
            serverDir,
            InstalledVersion(source = "hangar", id = "EssentialsX", versionName = "2.22.0", file = "EssentialsX.jar"),
        )
        // "owner/repo" declared but never installed -- should show "not installed".

        val result = Jarlet().test(listOf("plugin", "list", "myserver"))

        assertEquals(0, result.statusCode)
        assertTrue(result.stdout.contains("EssentialsX"), "got: ${result.stdout}")
        assertTrue(result.stdout.contains("2.22.0"), "got: ${result.stdout}")
        assertTrue(result.stdout.contains("pin: 2.22.0"), "got: ${result.stdout}")
        assertTrue(result.stdout.contains("owner/repo"), "got: ${result.stdout}")
        assertTrue(result.stdout.contains("not installed"), "got: ${result.stdout}")
        assertTrue(result.stdout.contains("channel: Release"), "got: ${result.stdout}")
    }

    @Test
    fun `matches declared and installed entries case-insensitively by id`() {
        val tomlFile = writeServerToml("myserver", defaultToml(plugins = listOf(declaredPlugin("hangar", "Geyser"))))
        val serverDir = tomlFile.parent
        PluginStateStore.write(
            serverDir,
            InstalledVersion(source = "hangar", id = "geyser", versionName = "2.4.2", file = "Geyser.jar"),
        )

        val result = Jarlet().test(listOf("plugin", "list", "myserver"))

        assertEquals(0, result.statusCode)
        assertTrue(result.stdout.contains("2.4.2"), "expected the differently-cased installed entry to still match: ${result.stdout}")
        assertTrue(!result.stdout.contains("not installed"), "got: ${result.stdout}")
    }

    @Test
    fun `caches and displays a Spiget resource's real name alongside its numeric id`() {
        val tomlFile = writeServerToml("myserver", defaultToml(plugins = listOf(declaredPlugin("spiget", "12345"))))
        val serverDir = tomlFile.parent
        PluginStateStore.write(
            serverDir,
            InstalledVersion(
                source = "spiget",
                id = "12345",
                versionName = "1.0.0",
                file = "plugin.jar",
                displayName = "EssentialsX",
            ),
        )

        val result = Jarlet().test(listOf("plugin", "list", "myserver"))

        assertEquals(0, result.statusCode)
        assertTrue(result.stdout.contains("EssentialsX (12345)"), "got: ${result.stdout}")
    }

    @Test
    fun `a Spiget resource declared but never installed still falls back to the bare numeric id`() {
        writeServerToml("myserver", defaultToml(plugins = listOf(declaredPlugin("spiget", "67890"))))

        val result = Jarlet().test(listOf("plugin", "list", "myserver"))

        assertEquals(0, result.statusCode)
        assertTrue(result.stdout.contains("67890"), "got: ${result.stdout}")
        assertTrue(!result.stdout.contains("null"), "got: ${result.stdout}")
    }

    @Test
    fun `--all bypasses pagination and shows every declared plugin without a page footer`() {
        // Zero-padded so lexicographic sortId ordering (ListCommand sorts by
        // source, then raw declared id) matches numeric order.
        val plugins = (1..15).map { declaredPlugin("hangar", "plugin-%02d".format(it)) }
        writeServerToml("myserver", defaultToml(plugins = plugins))

        val result = Jarlet().test(listOf("plugin", "list", "myserver", "--all"))

        assertEquals(0, result.statusCode)
        for (i in 1..15) {
            val id = "plugin-%02d".format(i)
            assertTrue(result.stdout.contains(id), "expected $id in --all output, got: ${result.stdout}")
        }
        assertTrue(!result.stdout.contains("Page"), "expected no page footer with --all, got: ${result.stdout}")
    }

    @Test
    fun `default pagination shows only the first page and a footer pointing to the next one`() {
        // PLUGIN_LIST_PAGE_SIZE defaults to 10 (jarlet-sys.conf). Zero-padded
        // ids so lexicographic sortId ordering matches numeric order.
        val plugins = (1..15).map { declaredPlugin("hangar", "plugin-%02d".format(it)) }
        writeServerToml("myserver", defaultToml(plugins = plugins))

        val result = Jarlet().test(listOf("plugin", "list", "myserver"))

        assertEquals(0, result.statusCode)
        assertTrue(result.stdout.contains("plugin-01"), "got: ${result.stdout}")
        assertTrue(!result.stdout.contains("plugin-15"), "expected page 2's entries to be absent, got: ${result.stdout}")
        assertTrue(result.stdout.contains("Page 1 of 2"), "got: ${result.stdout}")
        assertTrue(result.stdout.contains("--page 2"), "got: ${result.stdout}")
    }

    private fun declaredPlugin(
        source: String,
        id: String,
        pin: String? = null,
        channel: String? = null,
    ): JarletToml.Plugin = JarletToml.Plugin(
        source = source,
        id = id,
        policy = if (pin != null) {
            JarletToml.Plugin.Policy(pin = pin)
        } else {
            JarletToml.Plugin.Policy(track = "channel", channel = channel ?: "Release")
        },
    )
}
