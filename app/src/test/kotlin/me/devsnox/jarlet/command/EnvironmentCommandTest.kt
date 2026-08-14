package me.devsnox.jarlet.command

import me.devsnox.jarlet.Jarlet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnvironmentCommandTest : CommandTestSupport() {
    @Test
    fun `environment lifecycle creates selects lists and removes a namespace`() {
        assertEquals(0, Jarlet().test(listOf("env", "create", "prod")).statusCode)
        assertEquals(0, Jarlet().test(listOf("env", "use", "prod")).statusCode)

        val current = Jarlet().test(listOf("env", "current"))
        assertEquals(0, current.statusCode)
        assertTrue(current.output.contains("prod"), current.output)

        val list = Jarlet().test(listOf("env", "list"))
        assertEquals(0, list.statusCode)
        assertTrue(list.output.contains("prod *"), list.output)

        assertEquals(0, Jarlet().test(listOf("env", "remove", "prod")).statusCode)
        assertTrue(Jarlet().test(listOf("env", "list")).output.isBlank())
    }
}
