package me.devsnox.jarlet.topology

import me.devsnox.jarlet.service.JarletServiceException

/** Typed transport boundary for a running Velocity management integration. */
interface VelocityManagementTransport {
    fun listBackends(): Set<RegisteredBackend>
    fun registerBackend(backend: RegisteredBackend)
    fun unregisterBackend(name: String)
}

/** Low-level command executor used by the optional command-protocol adapter. */
interface VelocityCommandTransport {
    fun execute(command: String): String
}

/**
 * Adapts the `jarlet-backend` management command protocol to the typed
 * transport used by [VelocityProvider]. A console adapter, RCON bridge, or
 * management plugin can instead implement [VelocityManagementTransport]
 * directly without exposing command strings to topology code.
 */
class VelocityCommandManagementTransport(
    private val transport: VelocityCommandTransport,
) : VelocityManagementTransport {
    override fun listBackends(): Set<RegisteredBackend> = execute("jarlet-backend list").lineSequence().mapNotNull { line ->
            val fields = line.trim().split(Regex("\\s+"), limit = 2)
            if (fields.size == 2 && fields[0].isNotBlank()) RegisteredBackend(fields[0], fields[1]) else null
        }.toSet()

    override fun registerBackend(backend: RegisteredBackend) {
        execute("jarlet-backend register ${argument(backend.name)} ${argument(backend.address)}")
    }

    override fun unregisterBackend(name: String) {
        execute("jarlet-backend unregister ${argument(name)}")
    }

    private fun execute(command: String): String = try {
        transport.execute(command)
    } catch (e: Exception) {
        throw JarletServiceException.ExternalSourceError("Velocity is unavailable: ${e.message}", e)
    }

    private fun argument(value: String): String {
        if (value.isBlank() || value.any { it.isWhitespace() || it == '"' }) {
            throw JarletServiceException.InvalidInput("Invalid Velocity backend value")
        }
        return value
    }
}

/** Provider-facing Velocity integration; it is independent of command syntax. */
class VelocityProvider(
    private val transport: VelocityManagementTransport,
) : ProxyProvider {
    override fun discover(): TopologyState = try {
        TopologyState(transport.listBackends())
    } catch (e: Exception) {
        throw unavailable(e)
    }

    override fun register(backend: RegisteredBackend) = try {
        transport.registerBackend(backend)
    } catch (e: Exception) {
        throw unavailable(e)
    }

    override fun unregister(name: String) = try {
        transport.unregisterBackend(name)
    } catch (e: Exception) {
        throw unavailable(e)
    }

    private fun unavailable(e: Exception): JarletServiceException = when (e) {
        is JarletServiceException -> e
        else -> JarletServiceException.ExternalSourceError("Velocity is unavailable: ${e.message}", e)
    }
}
