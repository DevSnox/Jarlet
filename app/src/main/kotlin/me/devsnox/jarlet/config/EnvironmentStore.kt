package me.devsnox.jarlet.config

import me.devsnox.jarlet.instance.InstanceRef
import me.devsnox.jarlet.service.JarletServiceException
import org.tomlj.Toml
import java.nio.file.Files
import java.nio.file.Path

/** Optional root-level environment metadata; instance TOMLs remain authoritative. */
data class EnvironmentDefinition(
    val name: String,
    val description: String? = null,
)

object EnvironmentStore {
    fun path(root: Path): Path = root.resolve(SysConfig.default().value("ENVIRONMENTS_FILENAME"))

    fun read(root: Path): List<EnvironmentDefinition> {
        val file = path(root)
        if (!Files.isRegularFile(file)) return emptyList()
        val parsed = Toml.parse(file)
        if (parsed.hasErrors()) {
            throw JarletServiceException.InvalidInput("Could not parse $file as TOML: ${parsed.errors().joinToString()}")
        }
        val table = parsed.getTable("environments") ?: return emptyList()
        return table.keySet().sorted().map { name ->
            InstanceRef.of(name)
            val environment = table.getTable(name)
            EnvironmentDefinition(name, environment?.getString("description"))
        }
    }
}
