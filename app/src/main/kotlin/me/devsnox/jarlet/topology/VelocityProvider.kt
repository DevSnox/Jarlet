package me.devsnox.jarlet.topology

import me.devsnox.jarlet.service.JarletServiceException

/** Small transport boundary for a running Velocity management plugin/console. */
interface VelocityCommandTransport {
    fun execute(command: String): String
}

/**
 * Velocity integration through a management command transport. The transport
 * may be a console adapter, RCON bridge, or management plugin; no topology
 * code depends on how the proxy process is started.
 */
class VelocityProvider(
    private val transport: VelocityCommandTransport,
) : ProxyProvider {
    override fun discover(): TopologyState {
        val response = execute("jarlet-backend list")
        val entries = response.lineSequence().mapNotNull { line ->
            val fields = line.trim().split(Regex("\\s+"), limit = 2)
            if (fields.size == 2 && fields[0].isNotBlank()) RegisteredBackend(fields[0], fields[1]) else null
        }.toSet()
        return TopologyState(entries)
    }

    override fun register(backend: RegisteredBackend) {
        execute("jarlet-backend register ${argument(backend.name)} ${argument(backend.address)}")
    }

    override fun unregister(name: String) {
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
