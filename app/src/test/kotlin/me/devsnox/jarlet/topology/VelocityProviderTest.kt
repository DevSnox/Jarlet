package me.devsnox.jarlet.topology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import me.devsnox.jarlet.service.JarletServiceException

class VelocityProviderTest {
    @Test
    fun `discovers and mutates backends through the management transport`() {
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
        val provider = VelocityProvider(transport)

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
        val provider = VelocityProvider(object : VelocityCommandTransport {
            override fun execute(command: String): String = error("connection refused")
        })

        assertFailsWith<JarletServiceException.ExternalSourceError> {
            provider.discover()
        }
    }
}
