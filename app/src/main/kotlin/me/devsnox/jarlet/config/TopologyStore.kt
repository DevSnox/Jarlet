package me.devsnox.jarlet.config

import me.devsnox.jarlet.instance.InstanceRef
import me.devsnox.jarlet.service.JarletServiceException
import me.devsnox.jarlet.topology.BackendRef
import me.devsnox.jarlet.topology.Topology
import me.devsnox.jarlet.topology.TopologyScope
import org.tomlj.Toml
import java.nio.file.Files
import java.nio.file.Path

/** Reads optional desired topology state; runtime registration remains provider-owned. */
object TopologyStore {
    fun path(root: Path): Path = root.resolve(SysConfig.default().value("TOPOLOGY_FILENAME"))

    fun read(root: Path): Topology? {
        val file = path(root)
        if (!Files.isRegularFile(file)) return null
        val parsed = Toml.parse(file)
        if (parsed.hasErrors()) {
            throw JarletServiceException.InvalidInput("Could not parse $file as TOML: ${parsed.errors().joinToString()}")
        }
        val table = parsed.getTable("topology") ?: return null
        val proxy = table.getString("proxy")?.let(InstanceRef::parse)
            ?: throw JarletServiceException.InvalidInput("$file is missing topology.proxy")
        val scope = when (table.getString("scope")?.lowercase() ?: "global") {
            "global" -> TopologyScope.GLOBAL
            "environment", "environment-local" -> TopologyScope.ENVIRONMENT
            else -> throw JarletServiceException.InvalidInput("$file has an invalid topology.scope")
        }
        val backends = table.getArray("backends")?.let { array ->
            buildSet {
                for (index in 0 until array.size()) {
                    val backend = array.getTable(index)
                    val instance = backend.getString("instance")?.let(InstanceRef::parse)
                        ?: throw JarletServiceException.InvalidInput("$file has a backend missing instance")
                    val address = backend.getString("address")
                        ?: throw JarletServiceException.InvalidInput("$file has a backend missing address")
                    add(BackendRef(instance, address))
                }
            }
        } ?: emptySet()
        return Topology(proxy, scope, backends)
    }
}
