package me.devsnox.jarlet.plugin

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Coverage for [PluginYamlReader.read]'s extraction of the minimal
 * `name`/`version`/`main` set plus `depend`/`softdepend`, per
 * `prototyping/documentation/sources/plugin-yml-format.md`, against
 * hand-built jars shaped like real-world plugins (Geyser, ViaVersion,
 * Vault), and its fail-closed guards (missing entry, missing required
 * field, unresolved `${...}` version placeholder, corrupt YAML, corrupt
 * zip, non-existent path).
 */
class PluginYamlReaderTest {

    private val tempFiles = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        tempFiles.forEach { it.deleteIfExists() }
    }

    private fun jarWithPluginYaml(yaml: String?, entryName: String = "plugin.yml"): Path {
        val path = Files.createTempFile("jarlet-test-", ".jar")
        tempFiles += path
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            if (yaml != null) {
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(yaml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            } else {
                // A jar with unrelated content but no plugin.yml entry at all.
                zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
                zip.write("Manifest-Version: 1.0\n".toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return path
    }

    @Test
    fun `extracts name, version and main from a Geyser-like plugin yml`() {
        val yaml = """
            name: Geyser-Spigot
            version: 2.4.2-SNAPSHOT
            main: org.geysermc.geyser.platform.spigot.GeyserSpigotPlugin
            softdepend: [ViaVersion, floodgate]
        """.trimIndent()
        val jar = jarWithPluginYaml(yaml)

        val info = PluginYamlReader.read(jar)

        assertEquals(
            PluginYamlInfo(
                name = "Geyser-Spigot",
                version = "2.4.2-SNAPSHOT",
                main = "org.geysermc.geyser.platform.spigot.GeyserSpigotPlugin",
                depend = emptyList(),
                softdepend = listOf("ViaVersion", "floodgate"),
            ),
            info,
        )
    }

    @Test
    fun `extracts name, version and main from a ViaVersion-like plugin yml`() {
        val yaml = """
            name: ViaVersion
            version: 4.10.0
            main: com.viaversion.viaversion.bukkit.platform.BukkitPlugin
        """.trimIndent()
        val jar = jarWithPluginYaml(yaml)

        val info = PluginYamlReader.read(jar)

        assertEquals(
            PluginYamlInfo(
                name = "ViaVersion",
                version = "4.10.0",
                main = "com.viaversion.viaversion.bukkit.platform.BukkitPlugin",
                depend = emptyList(),
                softdepend = emptyList(),
            ),
            info,
        )
    }

    @Test
    fun `extracts name, version, main and hard depend from a Vault-like plugin yml`() {
        val yaml = """
            name: Vault
            version: 1.7.3
            main: net.milkbowl.vault.Vault
            depend: [Essentials]
        """.trimIndent()
        val jar = jarWithPluginYaml(yaml)

        val info = PluginYamlReader.read(jar)

        assertEquals(
            PluginYamlInfo(
                name = "Vault",
                version = "1.7.3",
                main = "net.milkbowl.vault.Vault",
                depend = listOf("Essentials"),
                softdepend = emptyList(),
            ),
            info,
        )
    }

    @Test
    fun `returns null for a Geyser-like plugin yml with an unresolved build placeholder version`() {
        val yaml = """
            name: Geyser-Spigot
            version: ${'$'}{project.version}
            main: org.geysermc.geyser.platform.spigot.GeyserSpigotPlugin
        """.trimIndent()
        val jar = jarWithPluginYaml(yaml)

        assertNull(PluginYamlReader.read(jar))
    }

    @Test
    fun `returns null when the jar has no plugin yml entry at all`() {
        val jar = jarWithPluginYaml(null)

        assertNull(PluginYamlReader.read(jar))
    }

    @Test
    fun `returns null when plugin yml is missing the required main field`() {
        val yaml = """
            name: ViaVersion
            version: 4.10.0
        """.trimIndent()
        val jar = jarWithPluginYaml(yaml)

        assertNull(PluginYamlReader.read(jar))
    }

    @Test
    fun `returns null when plugin yml is missing the required name field`() {
        val yaml = """
            version: 1.7.3
            main: net.milkbowl.vault.Vault
        """.trimIndent()
        val jar = jarWithPluginYaml(yaml)

        assertNull(PluginYamlReader.read(jar))
    }

    @Test
    fun `returns null when plugin yml is missing the required version field`() {
        val yaml = """
            name: Vault
            main: net.milkbowl.vault.Vault
        """.trimIndent()
        val jar = jarWithPluginYaml(yaml)

        assertNull(PluginYamlReader.read(jar))
    }

    @Test
    fun `returns null for a non-existent jar path without throwing`() {
        val missing = Files.createTempFile("jarlet-missing-", ".jar")
        missing.deleteIfExists()

        assertNull(PluginYamlReader.read(missing))
    }

    @Test
    fun `returns null for a corrupt non-zip file at the jar path without throwing`() {
        val path = Files.createTempFile("jarlet-corrupt-", ".jar")
        tempFiles += path
        Files.write(path, "not actually a zip file".toByteArray(Charsets.UTF_8))

        assertNull(PluginYamlReader.read(path))
    }

    @Test
    fun `returns null for malformed unparseable yaml in plugin yml without throwing`() {
        val yaml = """
            name: Vault
            main: net.milkbowl.vault.Vault
            version: [unterminated flow sequence
        """.trimIndent()
        val jar = jarWithPluginYaml(yaml)

        assertNull(PluginYamlReader.read(jar))
    }

    @Test
    fun `depend and softdepend default to empty lists when absent from the yaml`() {
        val yaml = """
            name: ViaVersion
            version: 4.10.0
            main: com.viaversion.viaversion.bukkit.platform.BukkitPlugin
        """.trimIndent()
        val jar = jarWithPluginYaml(yaml)

        val info = PluginYamlReader.read(jar)

        assertEquals(emptyList(), info?.depend)
        assertEquals(emptyList(), info?.softdepend)
    }

    @Test
    fun `parses depend and softdepend as lists of strings when present`() {
        val yaml = """
            name: Geyser-Spigot
            version: 2.4.2
            main: org.geysermc.geyser.platform.spigot.GeyserSpigotPlugin
            depend: [ProtocolLib]
            softdepend: [ViaVersion, floodgate]
        """.trimIndent()
        val jar = jarWithPluginYaml(yaml)

        val info = PluginYamlReader.read(jar)

        assertEquals(listOf("ProtocolLib"), info?.depend)
        assertEquals(listOf("ViaVersion", "floodgate"), info?.softdepend)
    }
}
