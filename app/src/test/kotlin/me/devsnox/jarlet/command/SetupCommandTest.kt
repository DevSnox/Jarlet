package me.devsnox.jarlet.command

import com.github.ajalt.clikt.testing.test
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import me.devsnox.jarlet.Jarlet
import me.devsnox.jarlet.config.write

/**
 * `jarlet setup <name> [template-file]` coverage restricted to paths that
 * never touch the network: argument/name validation, template
 * `[server].minecraft_version`/`.port`/`.package` validation, the
 * "already exists" guard, and a missing template-file argument.
 *
 * NOT covered here (needs a real network call, already out of scope for
 * this network-free suite): the success path, which requires
 * [me.devsnox.jarlet.adapter.server.PaperAdapter] to actually download a
 * server jar from `PAPER_API`. That would belong in `integrationTest`
 * alongside the other adapter smoke tests, not here.
 */
class SetupCommandTest : CommandTestSupport() {

    @Test
    fun `missing name argument is rejected`() {
        val result = Jarlet().test("setup")

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("name", ignoreCase = true), "expected a missing-argument message, got: ${result.stderr}")
    }

    @Test
    fun `an invalid server name is rejected before anything is created`() {
        val result = Jarlet().test("setup \"bad name!\"")

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("simple name"), "expected the name-validation message, got: ${result.stderr}")
        assertFalse(Files.exists(serversDir.resolve("bad name!")))
    }

    @Test
    fun `an invalid minecraft_version in the template is rejected and leaves no server directory behind`() {
        val templateFile = serversDir.resolve("template.toml")
        defaultToml(minecraftVersion = "not a version!").write(templateFile)

        val result = Jarlet().test(listOf("setup", "myserver", templateFile.toString()))

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("Invalid [server].minecraft_version"), "got: ${result.stderr}")
        assertFalse(Files.exists(serversDir.resolve("myserver")))
    }

    @Test
    fun `an out-of-range port in the template is rejected and leaves no server directory behind`() {
        val templateFile = serversDir.resolve("template.toml")
        defaultToml(port = 99999).write(templateFile)

        val result = Jarlet().test(listOf("setup", "myserver", templateFile.toString()))

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("Invalid [server].port"), "got: ${result.stderr}")
        assertFalse(Files.exists(serversDir.resolve("myserver")))
    }

    @Test
    fun `an unknown server package is rejected and leaves no server directory behind`() {
        val templateFile = serversDir.resolve("template.toml")
        defaultToml(pkg = "not-a-real-package").write(templateFile)

        val result = Jarlet().test(listOf("setup", "myserver", templateFile.toString()))

        assertEquals(1, result.statusCode)
        assertTrue(
            result.stderr.contains("Unknown [server].package 'not-a-real-package'"),
            "got: ${result.stderr}",
        )
        assertFalse(Files.exists(serversDir.resolve("myserver")))
    }

    @Test
    fun `setup refuses to overwrite an already-existing server instance`() {
        createServerDir("myserver")
        val templateFile = serversDir.resolve("template.toml")
        defaultToml().write(templateFile)

        val result = Jarlet().test(listOf("setup", "myserver", templateFile.toString()))

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("already exists"), "got: ${result.stderr}")
    }

    @Test
    fun `a non-existent template-file argument is rejected`() {
        val missingTemplate = serversDir.resolve("does-not-exist.toml")

        val result = Jarlet().test(listOf("setup", "myserver", missingTemplate.toString()))

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("does not exist"), "got: ${result.stderr}")
        assertFalse(Files.exists(serversDir.resolve("myserver")))
    }
}
