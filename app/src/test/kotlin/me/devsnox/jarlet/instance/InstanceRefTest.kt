package me.devsnox.jarlet.instance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import me.devsnox.jarlet.service.JarletServiceException

class InstanceRefTest {
    @Test
    fun `parses plain and namespaced references`() {
        assertEquals("survival", InstanceRef.parse("survival").toString())
        assertEquals("prod/survival", InstanceRef.parse("prod/survival").toString())
    }

    @Test
    fun `rejects traversal and deeper paths`() {
        assertFailsWith<JarletServiceException.InvalidInput> { InstanceRef.parse("../survival") }
        assertFailsWith<JarletServiceException.InvalidInput> { InstanceRef.parse("a/b/c") }
    }

    @Test
    fun `parses supported resource selectors`() {
        assertEquals(ResourceSelector.ServerPackage, ResourceSelector.parse("package.server"))
        assertEquals(ResourceSelector.AllPluginPackages, ResourceSelector.parse("package.plugin.*"))
        assertEquals(ResourceSelector.PluginPackage("hangar:LuckPerms"), ResourceSelector.parse("package.plugin.hangar:LuckPerms"))
        assertEquals(ResourceSelector.AllWorlds, ResourceSelector.parse("data.world.*"))
        assertEquals(ResourceSelector.All, ResourceSelector.parse("all"))
        assertFailsWith<JarletServiceException.InvalidInput> { ResourceSelector.parse("plugin.hangar:LuckPerms") }
        assertFailsWith<JarletServiceException.InvalidInput> { ResourceSelector.parse("config.server") }
        assertFailsWith<JarletServiceException.InvalidInput> { ResourceSelector.parse("data.world../secret") }
    }
}
