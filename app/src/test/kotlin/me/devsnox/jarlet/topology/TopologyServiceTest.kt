package me.devsnox.jarlet.topology

import kotlin.test.Test
import kotlin.test.assertEquals
import me.devsnox.jarlet.instance.InstanceRef

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
}
