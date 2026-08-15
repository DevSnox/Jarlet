package me.devsnox.jarlet.adapter.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ServerSoftwareAdaptersTest {
    @Test
    fun `paper and velocity resolve through the shared PaperMC adapter`() {
        val paper = ServerSoftwareAdapters.find("paper")
        val velocity = ServerSoftwareAdapters.find("velocity")

        assertSame(paper, velocity)
        assertEquals(setOf("paper", "velocity"), paper.supportedPackages)
        assertTrue(paper.isMinecraftServer("paper"))
        assertFalse(paper.isMinecraftServer("velocity"))
        assertEquals(listOf("nogui"), paper.launchArguments("paper"))
        assertEquals(emptyList(), paper.launchArguments("velocity"))
    }
}
