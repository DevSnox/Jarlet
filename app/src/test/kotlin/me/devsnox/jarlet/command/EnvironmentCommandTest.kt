package me.devsnox.jarlet.command

import me.devsnox.jarlet.Jarlet
import me.devsnox.jarlet.instance.InstanceResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnvironmentCommandTest : CommandTestSupport() {
    @Test
    fun `default is the initial environment and can be selected explicitly`() {
        assertTrue(Jarlet().test(listOf("env", "current")).output.contains("default"))
        assertTrue(Jarlet().test(listOf("env", "list")).output.contains("default *"))
        assertEquals(0, Jarlet().test(listOf("env", "use", "default")).statusCode)
    }

    @Test
    fun `environment lifecycle creates selects lists and removes a namespace`() {
        assertEquals(0, Jarlet().test(listOf("env", "create", "prod")).statusCode)
        assertEquals(0, Jarlet().test(listOf("env", "use", "prod")).statusCode)

        val current = Jarlet().test(listOf("env", "current"))
        assertEquals(0, current.statusCode)
        assertTrue(current.output.contains("prod"), current.output)
        assertEquals("prod/survival", InstanceResolver().resolve("survival").toString())

        val list = Jarlet().test(listOf("env", "list"))
        assertEquals(0, list.statusCode)
        assertTrue(list.output.contains("prod *"), list.output)

        assertEquals(0, Jarlet().test(listOf("env", "remove", "prod")).statusCode)
        val afterRemove = Jarlet().test(listOf("env", "list"))
        assertTrue(afterRemove.output.contains("default *"), afterRemove.output)
    }
}
