package me.devsnox.jarlet.service

import me.devsnox.jarlet.instance.InstanceRef
import me.devsnox.jarlet.instance.InstanceResolver
import java.nio.file.Files

/** Application service for discovering and validating namespaced instances. */
object EnvironmentService {
    data class InstanceSummary(val ref: InstanceRef, val directory: java.nio.file.Path, val configured: Boolean)

    fun resolve(value: String): InstanceSummary {
        val resolver = InstanceResolver()
        val ref = resolver.resolve(value)
        val directory = resolver.directory(ref)
        return InstanceSummary(ref, directory, Files.isRegularFile(resolver.config(ref)))
    }

    fun list(environment: String? = null): List<InstanceSummary> {
        val resolver = InstanceResolver()
        if (!Files.isDirectory(resolver.root())) return emptyList()
        return Files.walk(resolver.root(), 2).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString() == "jarlet.toml" }
                .map { path ->
                    val relative = resolver.root().relativize(path.parent)
                    val parts = relative.iterator().asSequence().map { it.toString() }.toList()
                    val ref = if (parts.size == 1) InstanceRef.of(parts[0]) else InstanceRef.of(parts[1], parts[0])
                    InstanceSummary(ref, path.parent, configured = true)
                }
                .toList()
                .filter { environment == null || it.ref.environment == environment }
                .sortedBy { it.ref.toString() }
        }
    }
}
