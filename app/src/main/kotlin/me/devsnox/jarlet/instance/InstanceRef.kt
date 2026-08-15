package me.devsnox.jarlet.instance

import me.devsnox.jarlet.config.SysConfig
import me.devsnox.jarlet.config.EnvironmentStore
import me.devsnox.jarlet.service.JarletServiceException
import java.nio.file.Path
import java.nio.file.Paths

/** Stable domain identity for an instance, independent of its filesystem path. */
data class InstanceRef private constructor(
    val environment: String?,
    val name: String,
) {
    override fun toString(): String = environment?.let { "$it/$name" } ?: name

    companion object {
        private val NAME = Regex("^[0-9A-Za-z_-]+$")

        fun of(name: String, environment: String? = null): InstanceRef {
            validatePart(name, "instance")
            environment?.let { validatePart(it, "environment") }
            return InstanceRef(environment, name)
        }

        /** Parses `name` or `environment/name`; deeper paths are rejected. */
        fun parse(value: String): InstanceRef {
            val parts = value.split('/')
            return when (parts.size) {
                1 -> of(parts[0])
                2 -> of(parts[1], parts[0])
                else -> throw JarletServiceException.InvalidInput(
                    "Instance must be <name> or <environment>/<name>",
                )
            }
        }

        private fun validatePart(value: String, kind: String) {
            if (!NAME.matches(value)) {
                throw JarletServiceException.InvalidInput(
                    "$kind must be a simple name containing only letters, digits, _ and -",
                )
            }
        }
    }
}

/** Resolves an [InstanceRef] to storage without exposing layout to services. */
class InstanceResolver(
    private val root: Path = defaultRoot(),
) {
    companion object {
        const val DEFAULT_NAMESPACE = "default"
        const val CONTROL_ROOT_PROPERTY = "jarlet.controlRoot"

        private const val INSTANCE_ROOT_PROPERTY = "jarlet.instancesDir"

        fun defaultRoot(): Path {
            val override = System.getProperty(INSTANCE_ROOT_PROPERTY)
                ?: System.getProperty("jarlet.serversDir")
                ?: System.getenv("JARLET_INSTANCES_DIR")
            if (!override.isNullOrEmpty()) {
                val path = Paths.get(override)
                if (!path.isAbsolute) {
                    throw JarletServiceException.InvalidInput("JARLET_INSTANCES_DIR must be an absolute path")
                }
                return path
            }
            return Paths.get(SysConfig.default().value("INSTANCES_DIR_DEFAULT").replace("\$HOME", System.getProperty("user.home")))
        }

        fun controlRoot(instancesRoot: Path): Path =
            System.getProperty(CONTROL_ROOT_PROPERTY)?.let(Paths::get) ?: instancesRoot.parent
    }

    fun root(): Path = root

    fun directory(ref: InstanceRef): Path {
        val namespace = ref.environment
            ?: EnvironmentStore.current(root)
        return root.resolve(namespace).resolve(ref.name)
    }

    fun config(ref: InstanceRef): Path = directory(ref).resolve(SysConfig.default().value("TEMPLATE_FILENAME"))

    fun resolve(value: String): InstanceRef {
        val parsed = InstanceRef.parse(value)
        if (parsed.environment != null) return parsed
        return InstanceRef.of(parsed.name, EnvironmentStore.current(root))
    }
}
