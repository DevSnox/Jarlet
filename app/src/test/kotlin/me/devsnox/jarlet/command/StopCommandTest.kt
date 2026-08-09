package me.devsnox.jarlet.command

import com.github.ajalt.clikt.testing.test
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import me.devsnox.jarlet.Jarlet

/**
 * `jarlet stop <name>` coverage -- entirely network-free and process-spawn-
 * free by construction (stop.sh's own logic never touches either): argument
 * validation, the "no such server" / "not running" / "stale PID" guards,
 * and the "PID does not look like the Paper server" guard, exercised using
 * this test JVM's own PID (a real, live process whose command line is
 * guaranteed not to contain `-jar server.jar`).
 */
class StopCommandTest : CommandTestSupport() {

    @Test
    fun `missing name argument is rejected`() {
        val result = Jarlet().test("stop")

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("name", ignoreCase = true), "got: ${result.stderr}")
    }

    @Test
    fun `stopping a server that does not exist is rejected`() {
        val result = Jarlet().test(listOf("stop", "no-such-server"))

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("No server named 'no-such-server' found"), "got: ${result.stderr}")
    }

    @Test
    fun `stopping a server with no recorded PID reports it is not running`() {
        createServerDir("myserver")

        val result = Jarlet().test(listOf("stop", "myserver"))

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("Server is not running"), "got: ${result.stderr}")
    }

    @Test
    fun `a stale PID file for a dead process is cleaned up and reported`() {
        val serverDir = createServerDir("myserver")
        val jarletDir = Files.createDirectories(serverDir.resolve(".jarlet"))
        val pidFile = jarletDir.resolve("server.pid")
        // A PID astronomically unlikely to be alive on any real machine.
        Files.writeString(pidFile, "999999999\n")

        val result = Jarlet().test(listOf("stop", "myserver"))

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("removed stale PID file"), "got: ${result.stderr}")
        assertTrue(Files.notExists(pidFile), "expected the stale PID file to be deleted")
    }

    @Test
    fun `a live PID whose command line is not the Paper server is rejected`() {
        val serverDir = createServerDir("myserver")
        val jarletDir = Files.createDirectories(serverDir.resolve(".jarlet"))
        val ownPid = ProcessHandle.current().pid()
        Files.writeString(jarletDir.resolve("server.pid"), "$ownPid\n")

        val result = Jarlet().test(listOf("stop", "myserver"))

        assertEquals(1, result.statusCode)
        assertTrue(
            result.stderr.contains("does not appear to be the Paper server"),
            "got: ${result.stderr}",
        )
    }

    @Test
    fun `an unparseable PID file is rejected`() {
        val serverDir = createServerDir("myserver")
        val jarletDir = Files.createDirectories(serverDir.resolve(".jarlet"))
        Files.writeString(jarletDir.resolve("server.pid"), "not-a-number\n")

        val result = Jarlet().test(listOf("stop", "myserver"))

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("Invalid server PID"), "got: ${result.stderr}")
    }
}
