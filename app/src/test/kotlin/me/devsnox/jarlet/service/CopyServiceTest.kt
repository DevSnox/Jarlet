package me.devsnox.jarlet.service

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import me.devsnox.jarlet.config.JarletToml
import me.devsnox.jarlet.config.write
import me.devsnox.jarlet.instance.InstanceRef
import me.devsnox.jarlet.instance.PluginPackageResource
import me.devsnox.jarlet.instance.ResourceSelector
import me.devsnox.jarlet.server.ServerPaths

class CopyServiceTest {
    private lateinit var root: Path

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("jarlet-copy-test-")
        System.setProperty(ServerPaths.SERVERS_DIR_PROPERTY, root.toString())
    }

    @AfterTest
    fun tearDown() {
        System.clearProperty(ServerPaths.SERVERS_DIR_PROPERTY)
        root.toFile().deleteRecursively()
    }

    @Test
    fun `copies selected world without unrelated resources`() {
        val source = root.resolve("prod/survival")
        val target = root.resolve("test/survival")
        Files.createDirectories(source.resolve("world"))
        Files.writeString(source.resolve("world/level.dat"), "world")
        Files.createDirectories(source.resolve("config"))
        Files.writeString(source.resolve("config/server.conf"), "source")
        writeToml(source, listOf(JarletToml.Plugin("hangar", "LuckPerms")))
        writeToml(target, emptyList())

        CopyService.copy(
            "prod/survival",
            "test/survival",
            setOf("data.world.world"),
        )

        assertTrue(Files.isRegularFile(target.resolve("world/level.dat")))
        assertTrue(!Files.exists(target.resolve("config/server.conf")))
    }

    @Test
    fun `plugin resolution describes declarations without exposing jar paths`() {
        val source = root.resolve("prod/survival")
        val target = root.resolve("test/survival")
        writeToml(source, listOf(JarletToml.Plugin("hangar", "LuckPerms")))
        writeToml(target, emptyList())

        val plan = CopyService.plan(
            CopyRequest(
                source = InstanceRef.parse("prod/survival"),
                target = InstanceRef.parse("test/survival"),
                selectors = setOf(ResourceSelector.AllPluginPackages),
            ),
        )

        val resource = plan.changes.single().resources.single() as PluginPackageResource
        kotlin.test.assertEquals("hangar:LuckPerms", resource.id)
    }

    private fun writeToml(dir: Path, plugins: List<JarletToml.Plugin>) {
        Files.createDirectories(dir)
        JarletToml(
            template = JarletToml.Template("test"),
            server = JarletToml.Server("paper", "1.21.1", "2G", 25565, true),
            plugins = plugins,
        ).write(dir.resolve(ServerPaths.templateFilename()))
    }
}
