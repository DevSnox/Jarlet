package me.devsnox.jarlet.server

import me.devsnox.jarlet.config.SysConfig
import me.devsnox.jarlet.instance.InstanceRef
import me.devsnox.jarlet.instance.InstanceResolver
import me.devsnox.jarlet.service.JarletServiceException
import java.nio.file.Path

/** Compatibility facade for lifecycle commands; identity/layout live in [InstanceRef]/[InstanceResolver]. */
object ServerPaths {
    /**
     * JVM-system-property override for [instancesRoot], checked before the
     * `JARLET_INSTANCES_DIR` env var. Real users/scripts have no reason to
     * ever set this -- the env var remains the one documented, real-world
     * override -- it exists purely so tests can point an entire command
     * invocation at a JUnit temp directory without touching the real
     * `~/jarlet/servers`. The JVM has no supported, portable way to mutate
     * `System.getenv()` at runtime, but `System.setProperty`/`clearProperty`
     * are ordinary, safe JVM APIs, which is why this is a system property
     * rather than an env-var-mutation hack.
     */
    internal const val SERVERS_DIR_PROPERTY = "jarlet.instancesDir"

    /** Validates a plain instance name or an `environment/name` reference. */
    fun validateName(name: String): String {
        InstanceRef.parse(name)
        return name
    }

    /** Resolves the physical `instances/` root. */
    fun instancesRoot(): Path = InstanceResolver.defaultRoot()

    /** Compatibility name for callers that still refer to the instance root as the servers root. */
    fun serversRoot(): Path = instancesRoot()

    /** Resolves a plain or namespaced instance under the physical `instances/` root. */
    fun serverDir(name: String): Path {
        val resolver = InstanceResolver(instancesRoot())
        return resolver.directory(resolver.resolve(validateName(name)))
    }

    /** The standard per-server template/instance filename (`jarlet.toml`). */
    fun templateFilename(): String = SysConfig.default().value("TEMPLATE_FILENAME")
}
