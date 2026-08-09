package me.devsnox.jarlet

import com.github.ajalt.clikt.testing.test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Root-command coverage: no subcommand at all, `--version`, and an unknown
 * subcommand name -- the dispatch-level behaviors that don't belong to any
 * one leaf command class. Exercised via Clikt's `CliktCommand.test()`
 * in-process test harness (see [me.devsnox.jarlet.command.CommandTestSupport]'s
 * doc comment for the exact API this was verified against).
 */
class JarletCommandTest {

    @Test
    fun `bare jarlet with no subcommand prints help instead of doing nothing`() {
        val result = Jarlet().test("")

        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("Usage:"), "expected a Usage: line in output, got: ${result.output}")
        assertTrue(
            result.output.contains("Manage Paper/Minecraft servers and their plugins."),
            "expected the root command's help text, got: ${result.output}",
        )
    }

    @Test
    fun `--version prints the jarlet version and exits 0`() {
        val result = Jarlet().test("--version")

        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("jarlet version"), "expected a version line, got: ${result.output}")
    }

    @Test
    fun `an unknown subcommand name is rejected with a non-zero exit`() {
        val result = Jarlet().test("not-a-real-subcommand")

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.isNotBlank(), "expected an error on stderr, got empty stderr")
    }

    @Test
    fun `--help lists every top-level subcommand`() {
        val result = Jarlet().test("--help")

        assertEquals(0, result.statusCode)
        for (subcommand in listOf("install", "setup", "start", "stop", "plugin")) {
            assertTrue(result.output.contains(subcommand), "expected \"$subcommand\" in --help output, got: ${result.output}")
        }
    }
}
