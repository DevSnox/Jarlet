package me.devsnox.jarlet.topology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import me.devsnox.jarlet.service.JarletServiceException

class VelocityProviderTest {
    @Test
    fun `provider discovers and mutates through typed management transport`() {
        val calls = mutableListOf<String>()
        val transport = object : VelocityManagementTransport {
            override fun listBackends(): Set<RegisteredBackend> {
                calls += "list"
                return setOf(RegisteredBackend("existing", "127.0.0.1:25565"))
            }

            override fun registerBackend(backend: RegisteredBackend) {
                calls += "register:${backend.name}:${backend.address}"
            }

            override fun unregisterBackend(name: String) {
                calls += "unregister:$name"
            }
        }
        val provider = VelocityProvider(transport)

        assertEquals(
            TopologyState(setOf(RegisteredBackend("existing", "127.0.0.1:25565"))),
            provider.discover(),
        )
        provider.register(RegisteredBackend("survival", "127.0.0.1:25566"))
        provider.unregister("survival")

        assertEquals(
            listOf("list", "register:survival:127.0.0.1:25566", "unregister:survival"),
            calls,
        )
    }

    @Test
    fun `command management adapter translates backend operations`() {
        val commands = mutableListOf<String>()
        val transport = object : VelocityCommandTransport {
            override fun execute(command: String): String {
                commands += command
                return if (command == "jarlet-backend list") {
                    "existing 127.0.0.1:25565"
                } else {
                    "ok"
                }
            }
        }
        val provider = VelocityProvider(VelocityCommandManagementTransport(transport))

        assertEquals(
            TopologyState(setOf(RegisteredBackend("existing", "127.0.0.1:25565"))),
            provider.discover(),
        )
        provider.register(RegisteredBackend("survival", "127.0.0.1:25566"))
        provider.unregister("survival")

        assertEquals(
            listOf(
                "jarlet-backend list",
                "jarlet-backend register survival 127.0.0.1:25566",
                "jarlet-backend unregister survival",
            ),
            commands,
        )
    }

    @Test
    fun `maps transport failure to a typed unavailable error`() {
        val provider = VelocityProvider(object : VelocityManagementTransport {
            override fun listBackends(): Set<RegisteredBackend> = error("connection refused")
            override fun registerBackend(backend: RegisteredBackend) = error("connection refused")
            override fun unregisterBackend(name: String) = error("connection refused")
        })

        assertFailsWith<JarletServiceException.ExternalSourceError> {
            provider.discover()
        }
    }

    @Test
    fun `preserves typed transport validation errors`() {
        val provider = VelocityProvider(object : VelocityManagementTransport {
            override fun listBackends(): Set<RegisteredBackend> = emptySet()
            override fun registerBackend(backend: RegisteredBackend) {
                throw JarletServiceException.InvalidInput("invalid backend")
            }
            override fun unregisterBackend(name: String) = Unit
        })

        assertFailsWith<JarletServiceException.InvalidInput> {
            provider.register(RegisteredBackend("survival", "bad address"))
        }
    }
}
