package me.devsnox.jarlet.service

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import me.devsnox.jarlet.config.JarletToml
import me.devsnox.jarlet.config.InstalledServer
import me.devsnox.jarlet.config.InstalledVersion
import me.devsnox.jarlet.config.PluginStateStore
import me.devsnox.jarlet.config.ServerStateStore
import me.devsnox.jarlet.config.write
import me.devsnox.jarlet.instance.InstanceRef
import me.devsnox.jarlet.instance.PluginPackageResource
import me.devsnox.jarlet.instance.ResourceResolver
import me.devsnox.jarlet.instance.ResourceSelector
import me.devsnox.jarlet.instance.ServerPackageResource
import me.devsnox.jarlet.instance.WorldResource
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

    @Test
    fun `all discovers arbitrary validated worlds but excludes server directories`() {
        val source = root.resolve("prod/survival")
        writeToml(source, emptyList())
        Files.createDirectories(source.resolve("custom-world"))
        Files.writeString(source.resolve("custom-world/level.dat"), "world")
        Files.createDirectories(source.resolve("plugins"))
        Files.writeString(source.resolve("plugins/level.dat"), "not a world")
        Files.createDirectories(source.resolve("logs"))
        Files.writeString(source.resolve("logs/level.dat"), "not a world")
        Files.createDirectories(source.resolve("looks-like-data"))

        val resources = ResourceResolver(source, JarletToml.read(source.resolve(ServerPaths.templateFilename())))
            .resolve(ResourceSelector.All)

        assertTrue(resources.any { it is ServerPackageResource })
        assertTrue(resources.any { it is WorldResource && it.id == "custom-world" })
        assertTrue(resources.none { it is WorldResource && it.id in setOf("plugins", "logs", "looks-like-data") })
    }

    @Test
    fun `server package plan preserves target runtime settings`() {
        val source = root.resolve("prod/survival")
        val target = root.resolve("test/survival")
        writeToml(source, emptyList(), memory = "2G", port = 25565)
        writeToml(target, emptyList(), memory = "6G", port = 25570)

        val plan = CopyService.plan(
            CopyRequest(
                source = InstanceRef.parse("prod/survival"),
                target = InstanceRef.parse("test/survival"),
                selectors = setOf(ResourceSelector.ServerPackage),
            ),
        )

        assertTrue(plan.targetToml.server.runtime.memory == "6G")
        assertTrue(plan.targetToml.server.runtime.port == 25570)
        assertTrue(plan.targetToml.server.packageInfo.name == "paper")
    }

    @Test
    fun `package copy replaces a target plugin declaration by logical id`() {
        val source = root.resolve("prod/survival")
        val target = root.resolve("test/survival")
        writeToml(source, listOf(JarletToml.Plugin("hangar", "LuckPerms")))
        writeToml(target, listOf(JarletToml.Plugin("github", "LuckPerms")))

        val plan = CopyService.plan(
            CopyRequest(
                source = InstanceRef.parse("prod/survival"),
                target = InstanceRef.parse("test/survival"),
                selectors = setOf(ResourceSelector.AllPluginPackages),
            ),
        )

        assertTrue(plan.targetToml.plugins.count { it.id == "LuckPerms" } == 1)
        assertTrue(plan.targetToml.plugins.single().source == "hangar")
    }

    @Test
    fun `resolved pin mode copies installed package versions without copying state files`() {
        val source = root.resolve("prod/survival")
        val target = root.resolve("test/survival")
        writeToml(source, listOf(JarletToml.Plugin("hangar", "LuckPerms")), memory = "2G", port = 25565)
        writeToml(target, emptyList(), memory = "6G", port = 25570)
        ServerStateStore.write(source, InstalledServer("paper", "1.21.4"))
        PluginStateStore.write(
            source,
            InstalledVersion("hangar", "LuckPerms", "5.4.0", file = "LuckPerms.jar"),
        )

        val plan = CopyService.plan(
            CopyRequest(
                source = InstanceRef.parse("prod/survival"),
                target = InstanceRef.parse("test/survival"),
                selectors = setOf(ResourceSelector.ServerPackage, ResourceSelector.AllPluginPackages),
                packageMode = PackageTransferMode.RESOLVED_PIN,
            ),
        )

        assertTrue(plan.targetToml.server.packageInfo.version == "1.21.4")
        assertTrue(plan.targetToml.server.policy.pin == "1.21.4")
        assertTrue(plan.targetToml.server.runtime.memory == "6G")
        assertTrue(plan.targetToml.server.runtime.port == 25570)
        assertTrue(plan.targetToml.plugins.single().policy.pin == "5.4.0")
        assertTrue(!Files.exists(target.resolve("plugins-state.json")))
    }

    @Test
    fun `environment copy applies only to matching configured instances`() {
        val source = root.resolve("prod/survival")
        val target = root.resolve("test/survival")
        val unmatched = root.resolve("prod/lobby")
        writeToml(source, emptyList())
        writeToml(target, emptyList())
        writeToml(unmatched, emptyList())
        Files.createDirectories(source.resolve("world"))
        Files.writeString(source.resolve("world/level.dat"), "prod-world")

        val result = CopyService.copyEnvironment(
            sourceEnvironment = "prod",
            targetEnvironment = "test",
            selectors = setOf(ResourceSelector.World("world")),
        )

        assertTrue(result.instances.map { it.target.toString() } == listOf("test/survival"))
        assertTrue(Files.readString(target.resolve("world/level.dat")) == "prod-world")
        assertTrue(!Files.exists(root.resolve("test/lobby")))
    }

    private fun writeToml(
        dir: Path,
        plugins: List<JarletToml.Plugin>,
        memory: String = "2G",
        port: Int = 25565,
    ) {
        Files.createDirectories(dir)
        JarletToml(
            template = JarletToml.Template("test"),
            server = JarletToml.Server(
                packageInfo = JarletToml.ServerPackage("paper", "1.21.1"),
                runtime = JarletToml.ServerRuntime(memory, port, true),
            ),
            plugins = plugins,
        ).write(dir.resolve(ServerPaths.templateFilename()))
    }
}
