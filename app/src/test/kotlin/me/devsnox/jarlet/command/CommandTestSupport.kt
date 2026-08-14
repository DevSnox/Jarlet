package me.devsnox.jarlet.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.testing.CliktCommandTestResult
import com.github.ajalt.clikt.testing.test as cliktTest
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import me.devsnox.jarlet.Log
import me.devsnox.jarlet.config.JarletToml
import me.devsnox.jarlet.config.write
import me.devsnox.jarlet.server.ServerPaths

/**
 * Shared harness for command-level tests: exercises real `jarlet` CLI
 * commands end-to-end via Clikt's own in-process
 * `com.github.ajalt.clikt.testing.CliktCommand.test(argv, ...)` extension
 * (verified against the real clikt-jvm-sources.jar for clikt 5.1.0 --
 * returns a `CliktCommandTestResult(stdout, stderr, output, statusCode)`),
 * NOT by spawning a real OS subprocess and NOT by shelling out to the
 * packaged jar/native binary.
 *
 * Every test needs [me.devsnox.jarlet.server.ServerPaths.serversRoot] to
 * resolve to an isolated, disposable directory instead of the real
 * `~/jarlet/servers`. `serversRoot()` already supports a
 * `$JARLET_SERVERS_DIR` env-var override, but the JVM has no supported,
 * portable way to mutate `System.getenv()` per-test at runtime -- so
 * `ServerPaths` was given a companion `SERVERS_DIR_PROPERTY` **JVM system
 * property** override (checked before the env var), which
 * `System.setProperty`/`clearProperty` handle cleanly. Real users/scripts
 * have no reason to ever set it; it exists purely for this test harness.
 *
 * [setUpServersDir]/[tearDownServersDir] run around every test method
 * (`@BeforeTest`/`@AfterTest`, same as this project's other test classes),
 * so each test gets its own fresh temp directory and never observes another
 * test's state.
 */
abstract class CommandTestSupport {

    protected lateinit var serversDir: Path

    /**
     * Shadows `com.github.ajalt.clikt.testing.CliktCommand.test(argv: List<String>)`
     * for every subclass (Kotlin resolves a member function ahead of an
     * imported top-level extension with the same signature, so
     * `Jarlet().test(listOf(...))` in the 8 subclasses below picks this up
     * with zero per-test changes needed).
     *
     * Clikt's real `test()` only captures output written through the
     * Mordant `Terminal`/`TerminalRecorder` it installs into the command's
     * `Context` for the call -- confirmed by reading both
     * `clikt-mordant-jvm-5.1.0-sources.jar`'s `CliktTesting.kt` (its own
     * doc comment: "Anything printed with print or println is not
     * [captured]") and `mordant-jvm-3.0.2-sources.jar`'s
     * `TerminalRecorder.kt` (a private `StringBuilder`, entirely unrelated
     * to `System.out`/`System.err`). [me.devsnox.jarlet.Log] intentionally
     * has no `Context` to pull a `Terminal` from (see its doc comment), so
     * its output can never land in that recorder -- it has to be captured
     * separately and merged in here instead.
     */
    protected fun CliktCommand.test(argv: List<String>): CliktCommandTestResult {
        val outBuffer = ByteArrayOutputStream()
        val errBuffer = ByteArrayOutputStream()
        val previousOut = Log.out
        val previousErr = Log.err
        Log.out = PrintStream(outBuffer, true)
        Log.err = PrintStream(errBuffer, true)
        try {
            val result = this.cliktTest(argv)
            val logStdout = outBuffer.toString()
            val logStderr = errBuffer.toString()
            return result.copy(
                stdout = result.stdout + logStdout,
                stderr = result.stderr + logStderr,
                output = result.output + logStdout + logStderr,
            )
        } finally {
            Log.out = previousOut
            Log.err = previousErr
        }
    }

    @BeforeTest
    fun setUpServersDir() {
        serversDir = Files.createTempDirectory("jarlet-command-test-")
        System.setProperty(ServerPaths.SERVERS_DIR_PROPERTY, serversDir.toString())
    }

    @AfterTest
    fun tearDownServersDir() {
        System.clearProperty(ServerPaths.SERVERS_DIR_PROPERTY)
        serversDir.toFile().deleteRecursively()
    }

    /** A minimal, valid [JarletToml] -- callers override just the fields relevant to what they're testing. */
    protected fun defaultToml(
        pkg: String = "paper",
        minecraftVersion: String = "1.21.1",
        memory: String = "2G",
        port: Int = 25565,
        onlineMode: Boolean = true,
        plugins: List<JarletToml.Plugin> = emptyList(),
    ): JarletToml = JarletToml(
        template = JarletToml.Template(name = "test-template", description = "A hand-crafted test template"),
        server = JarletToml.Server(
            packageInfo = JarletToml.ServerPackage(pkg, minecraftVersion),
            runtime = JarletToml.ServerRuntime(memory, port, onlineMode),
        ),
        plugins = plugins,
    )

    /** Creates the instance directory for [name] under the isolated [serversDir], without writing a jarlet.toml. */
    protected fun createServerDir(name: String): Path = Files.createDirectories(serversDir.resolve(name))

    /** Creates the instance directory for [name] and writes [toml] as its `jarlet.toml`, returning the toml file's path. */
    protected fun writeServerToml(name: String, toml: JarletToml = defaultToml()): Path {
        val dir = createServerDir(name)
        val tomlFile = dir.resolve(ServerPaths.templateFilename())
        toml.write(tomlFile)
        return tomlFile
    }
}
