package me.devsnox.jarlet.topology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import me.devsnox.jarlet.instance.InstanceRef
import me.devsnox.jarlet.service.JarletServiceException

class TopologyServiceTest {
    @Test
    fun `plans namespaced backend registration and removal`() {
        val calls = mutableListOf<String>()
        val provider = object : ProxyProvider {
            override fun discover() = TopologyState(setOf(RegisteredBackend("old", "127.0.0.1:25565")))
            override fun register(backend: RegisteredBackend) { calls += "register:${backend.name}" }
            override fun unregister(name: String) { calls += "unregister:$name" }
        }
        val desired = Topology(
            proxy = InstanceRef.parse("prod/proxy"),
            backends = setOf(BackendRef(InstanceRef.parse("prod/survival"), "127.0.0.1:25566")),
        )

        val changes = TopologyService(provider).reconcile(desired)

        assertEquals(
            listOf("unregister:old", "register:prod-survival"),
            calls,
        )
        assertEquals(2, changes.size)
    }

    @Test
    fun `environment local topology uses instance names without environment prefix`() {
        val registered = mutableListOf<RegisteredBackend>()
        val provider = object : ProxyProvider {
            override fun discover() = TopologyState(emptySet())
            override fun register(backend: RegisteredBackend) { registered += backend }
            override fun unregister(name: String) = Unit
        }

        TopologyService(provider).reconcile(
            Topology(
                proxy = InstanceRef.parse("prod/proxy"),
                scope = TopologyScope.ENVIRONMENT,
                backends = setOf(BackendRef(InstanceRef.parse("prod/survival"), "127.0.0.1:25566")),
            ),
        )

        assertEquals(listOf(RegisteredBackend("survival", "127.0.0.1:25566")), registered)
    }

    @Test
    fun `environment local topology rejects a backend from another environment`() {
        assertFailsWith<JarletServiceException.InvalidInput> {
            Topology(
                proxy = InstanceRef.parse("prod/proxy"),
                scope = TopologyScope.ENVIRONMENT,
                backends = setOf(BackendRef(InstanceRef.parse("test/survival"), "127.0.0.1:25566")),
            )
        }
    }
}
