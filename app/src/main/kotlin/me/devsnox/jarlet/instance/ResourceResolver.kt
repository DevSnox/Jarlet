package me.devsnox.jarlet.instance

import me.devsnox.jarlet.config.InstalledServer
import me.devsnox.jarlet.config.InstalledVersion
import me.devsnox.jarlet.config.JarletToml
import me.devsnox.jarlet.config.PluginStateStore
import me.devsnox.jarlet.config.ServerStateStore
import me.devsnox.jarlet.service.JarletServiceException
import java.nio.file.Files
import java.nio.file.Path

sealed interface ResolvedResource {
    val id: String
}

data class ServerPackageResource(
    override val id: String = "server",
    val declaration: JarletToml.Server,
    val installed: InstalledServer?,
) : ResolvedResource

data class PluginPackageResource(
    override val id: String,
    val declaration: JarletToml.Plugin,
    val installed: InstalledVersion?,
) : ResolvedResource

data class WorldResource(
    override val id: String,
    val directory: Path,
) : ResolvedResource

/** Resolves semantic package/world selectors against the standard instance root. */
class ResourceResolver(
    private val instanceRoot: Path,
    private val toml: JarletToml,
) {
    fun resolve(selector: ResourceSelector): List<ResolvedResource> = when (selector) {
        ResourceSelector.ServerPackage -> listOf(serverPackage())
        ResourceSelector.AllPluginPackages -> pluginPackages(toml.plugins)
        is ResourceSelector.PluginPackage -> pluginPackages(selectPlugins(selector.identifier))
        ResourceSelector.AllWorlds -> worlds(discoverWorldDirectories())
        is ResourceSelector.World -> {
            val directory = instanceRoot.resolve(selector.name)
            if (WorldDirectoryValidator.isWorld(directory)) worlds(listOf(directory)) else emptyList()
        }
        ResourceSelector.All -> listOf(serverPackage()) + pluginPackages(toml.plugins) + worlds(discoverWorldDirectories())
    }

    private fun serverPackage() = ServerPackageResource(
        declaration = toml.server,
        installed = ServerStateStore.read(instanceRoot),
    )

    private fun pluginPackages(entries: List<JarletToml.Plugin>) = entries.map { entry ->
        PluginPackageResource(
            id = "${entry.source}:${entry.id}",
            declaration = entry,
            installed = PluginStateStore.read(instanceRoot, entry.source, entry.id),
        )
    }

    private fun worlds(directories: List<Path>) = directories.map { WorldResource(it.fileName.toString(), it) }

    private fun selectPlugins(identifier: String): List<JarletToml.Plugin> {
        val matches = toml.plugins.filter {
            it.id.equals(identifier, ignoreCase = true) ||
                "${it.source}:${it.id}".equals(identifier, ignoreCase = true)
        }
        if (matches.isEmpty()) throw JarletServiceException.NotFound("No declared plugin matches '$identifier'")
        return matches
    }

    private fun discoverWorldDirectories(): List<Path> = Files.list(instanceRoot).use { paths ->
        paths.filter(WorldDirectoryValidator::isWorld).sorted().toList()
    }
}

/** Positive world validation, with known Jarlet/server directories excluded as defense-in-depth. */
object WorldDirectoryValidator {
    private val excludedNames = setOf("plugins", ".jarlet", "logs", "libraries")

    fun isWorld(path: Path): Boolean {
        val name = path.fileName?.toString() ?: return false
        if (!Files.isDirectory(path) || name in excludedNames || name.startsWith(".")) return false
        if (!Regex("^[0-9A-Za-z_.-]+$").matches(name)) return false

        // level.dat is the positive identity marker for a Minecraft world.
        // A region/DIM directory is optional for a newly-created world.
        return Files.isRegularFile(path.resolve("level.dat"))
    }
}
