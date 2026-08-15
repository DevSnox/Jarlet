package me.devsnox.jarlet.config

import me.devsnox.jarlet.instance.InstanceRef
import me.devsnox.jarlet.instance.InstanceResolver
import me.devsnox.jarlet.service.JarletServiceException
import org.tomlj.Toml
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Optional root-level environment metadata; instance TOMLs remain authoritative. */
data class EnvironmentDefinition(
    val name: String,
    val description: String? = null,
)

object EnvironmentStore {
    /** Environment metadata lives beside the physical `instances/` directory. */
    fun path(root: Path): Path = InstanceResolver.controlRoot(root).resolve(SysConfig.default().value("ENVIRONMENTS_FILENAME"))

    fun read(root: Path): List<EnvironmentDefinition> {
        return document(root).environments
    }

    fun create(root: Path, name: String) {
        validateName(name, allowDefault = false)
        val document = document(root)
        if (document.environments.any { it.name == name }) {
            throw JarletServiceException.Conflict("Environment '$name' already exists")
        }
        write(root, document.copy(environments = (document.environments + EnvironmentDefinition(name)).sortedBy { it.name }))
    }

    fun remove(root: Path, name: String) {
        validateName(name, allowDefault = false)
        val document = document(root)
        if (document.environments.none { it.name == name }) {
            throw JarletServiceException.NotFound("Environment '$name' does not exist")
        }
        if (Files.isDirectory(root.resolve(name)) && configuredInstanceExists(root.resolve(name))) {
            throw JarletServiceException.Conflict("Environment '$name' still contains configured instances")
        }
        write(
            root,
            document.copy(
                environments = document.environments.filterNot { it.name == name },
                current = document.current.takeUnless { it == name },
            ),
        )
    }

    fun use(root: Path, name: String) {
        validateName(name, allowDefault = true)
        val document = document(root)
        if (name == InstanceResolver.DEFAULT_NAMESPACE) {
            write(root, document.copy(current = null))
            return
        }
        if (document.environments.none { it.name == name }) {
            throw JarletServiceException.NotFound("Environment '$name' does not exist")
        }
        write(root, document.copy(current = name))
    }

    fun current(root: Path): String = document(root).current ?: InstanceResolver.DEFAULT_NAMESPACE

    private data class Document(
        val environments: List<EnvironmentDefinition>,
        val current: String? = null,
    )

    private fun document(root: Path): Document {
        val file = path(root)
        if (!Files.isRegularFile(file)) return Document(emptyList())
        val parsed = Toml.parse(file)
        if (parsed.hasErrors()) {
            throw JarletServiceException.InvalidInput("Could not parse $file as TOML: ${parsed.errors().joinToString()}")
        }
        val table = parsed.getTable("environments")
        val environments = table?.keySet()?.sorted()?.map { name ->
            InstanceRef.of(name)
            val environment = table.getTable(name)
            EnvironmentDefinition(name, environment?.getString("description"))
        } ?: emptyList()
        val current = (parsed.get("current") as? String)?.let { value ->
            if (value == InstanceResolver.DEFAULT_NAMESPACE) null else {
                validateName(value, allowDefault = false)
                value
            }
        }
        if (current != null && environments.none { it.name == current }) {
            throw JarletServiceException.InvalidInput("$file selects unknown environment '$current'")
        }
        return Document(environments, current)
    }

    private fun write(root: Path, document: Document) {
        val controlRoot = InstanceResolver.controlRoot(root)
        Files.createDirectories(controlRoot)
        val content = buildString {
            document.current?.let { append("current = \"").append(escape(it)).append("\"\n\n") }
            if (document.environments.isNotEmpty()) {
                document.environments.forEach { environment ->
                    append("[environments.").append(environment.name).append("]\n")
                    environment.description?.let {
                        append("description = \"").append(escape(it)).append("\"\n")
                    }
                    append('\n')
                }
            }
        }
        val temporary = Files.createTempFile(controlRoot, ".environments-", ".tmp")
        try {
            Files.writeString(temporary, content)
            Files.move(temporary, path(root), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (exception: Exception) {
            Files.deleteIfExists(temporary)
            throw JarletServiceException.OperationFailed("Could not write ${path(root)}: ${exception.message}", exception)
        }
    }

    private fun configuredInstanceExists(environmentRoot: Path): Boolean = Files.walk(environmentRoot).use { paths ->
        paths.anyMatch { Files.isRegularFile(it) && it.fileName.toString() == SysConfig.default().value("TEMPLATE_FILENAME") }
    }

    private fun validateName(name: String, allowDefault: Boolean) {
        InstanceRef.of("placeholder", name)
        if (!allowDefault && name == InstanceResolver.DEFAULT_NAMESPACE) {
            throw JarletServiceException.InvalidInput("Environment 'default' is reserved for the default namespace")
        }
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
}
