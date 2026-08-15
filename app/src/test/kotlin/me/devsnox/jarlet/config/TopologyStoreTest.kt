package me.devsnox.jarlet.config

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import me.devsnox.jarlet.instance.InstanceRef
import me.devsnox.jarlet.topology.TopologyScope

class TopologyStoreTest {
    @Test
    fun `reads namespaced topology and backend addresses`() {
        val root = Files.createTempDirectory("jarlet-topology-").resolve("instances")
        try {
            Files.createDirectories(root)
            Files.writeString(
                TopologyStore.path(root),
                """
                [topology]
                proxy = "prod/proxy"
                scope = "environment"

                [[topology.backends]]
                instance = "prod/survival"
                address = "127.0.0.1:25566"
                """.trimIndent(),
            )

            val topology = TopologyStore.read(root)!!

            assertEquals(InstanceRef.parse("prod/proxy"), topology.proxy)
            assertEquals(TopologyScope.ENVIRONMENT, topology.scope)
            assertEquals(1, topology.backends.size)
            assertEquals("127.0.0.1:25566", topology.backends.single().address)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
