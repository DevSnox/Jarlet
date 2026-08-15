package me.devsnox.jarlet.command

import com.github.ajalt.clikt.testing.test
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import me.devsnox.jarlet.Jarlet
import me.devsnox.jarlet.config.InstalledServer
import me.devsnox.jarlet.config.ServerStateStore

/**
 * `jarlet start <name> [template-file] [--foreground] [--accept-eula]`
 * coverage restricted to paths that never touch the network and never
 * spawn a real `java` subprocess: argument validation, the
 * `[server.runtime]`/`[server.package]` guards (hit by
 * hand-crafting a `jarlet.toml` so the "auto-setup if missing" fallback
 * never runs), the EULA-acceptance gate, and the "already running" guard.
 *
 * NOT covered here (needs a real network call or a real launched `java`
 * process, both out of scope for this network-free suite): actually
 * installing `server.jar` (adapter network fetch) and actually starting
 * Paper. Those would belong in `integrationTest`/manual verification, not
 * here.
 */
class StartCommandTest : CommandTestSupport() {

    @Test
    fun `missing name argument is rejected`() {
        val result = Jarlet().test("start")

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("name", ignoreCase = true), "got: ${result.stderr}")
    }

    @Test
    fun `an invalid server name is rejected`() {
        val result = Jarlet().test(listOf("start", "bad name!"))

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("simple name"), "got: ${result.stderr}")
    }

    @Test
    fun `an invalid server memory in an existing jarlet toml is rejected`() {
        writeServerToml("myserver", defaultToml(memory = "2GB"))

        val result = Jarlet().test(listOf("start", "myserver"))

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("[server.runtime].memory must look like 2G or 2048M"), "got: ${result.stderr}")
    }

    @Test
    fun `an invalid package version in an existing jarlet toml is rejected`() {
        writeServerToml("myserver", defaultToml(minecraftVersion = "not a version!"))

        val result = Jarlet().test(listOf("start", "myserver"))

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("Invalid [server.package].version"), "got: ${result.stderr}")
    }

    @Test
    fun `an unknown server package in an existing jarlet toml is rejected`() {
        writeServerToml("myserver", defaultToml(pkg = "not-a-real-package"))

        val result = Jarlet().test(listOf("start", "myserver"))

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("Unknown [server.package].name 'not-a-real-package'"), "got: ${result.stderr}")
    }

    @Test
    fun `starting without accepting the EULA and without eula txt already present is rejected`() {
        writeServerToml("myserver")

        val result = Jarlet().test(listOf("start", "myserver"))

        assertEquals(1, result.statusCode)
        assertTrue(result.stderr.contains("--accept-eula"), "got: ${result.stderr}")
    }

    @Test
    fun `starting refuses to run when a live PID is already recorded`() {
        val tomlFile = writeServerToml("myserver")
        val serverDir = tomlFile.parent

        // Pre-accept the EULA and drop in a placeholder server.jar, with a
        // matching server-state.json record, so the command never reaches
        // the network-dependent install step.
        Files.writeString(serverDir.resolve("eula.txt"), "eula=true\n")
        Files.writeString(serverDir.resolve("server.jar"), "not a real jar, just needs to exist")
        ServerStateStore.write(serverDir, InstalledServer(pkg = "paper", minecraftVersion = "1.21.1"))

        val jarletDir = Files.createDirectories(serverDir.resolve(".jarlet"))
        val ownPid = ProcessHandle.current().pid()
        Files.writeString(jarletDir.resolve("server.pid"), "$ownPid\n")

        val result = Jarlet().test(listOf("start", "myserver"))

        assertEquals(1, result.statusCode)
        assertTrue(
            result.stderr.contains("Server is already running with PID $ownPid"),
            "got: ${result.stderr}",
        )
    }

    @Test
    fun `matching server-state does not trigger a reinstall`() {
        val tomlFile = writeServerToml("myserver")
        val serverDir = tomlFile.parent

        Files.writeString(serverDir.resolve("eula.txt"), "eula=true\n")
        Files.writeString(serverDir.resolve("server.jar"), "not a real jar, just needs to exist")
        ServerStateStore.write(serverDir, InstalledServer(pkg = "paper", minecraftVersion = "1.21.1"))

        // A live PID short-circuits the command right after the
        // install/plugin-reconciliation block, before any subprocess is
        // spawned -- if a reinstall were wrongly attempted here, it would
        // hit the network (unavailable in this sandbox) and fail with a
        // different error before ever reaching this check.
        val jarletDir = Files.createDirectories(serverDir.resolve(".jarlet"))
        val ownPid = ProcessHandle.current().pid()
        Files.writeString(jarletDir.resolve("server.pid"), "$ownPid\n")

        val result = Jarlet().test(listOf("start", "myserver"))

        assertEquals(1, result.statusCode)
        assertTrue(
            result.stderr.contains("Server is already running with PID $ownPid"),
            "expected the already-running guard to be reached without a reinstall attempt, got: ${result.stderr}",
        )
    }

    @Test
    fun `a package version differing from the recorded server-state triggers a reinstall attempt`() {
        // A version that's pattern-valid (passes StartCommand's own regex
        // guard) but doesn't exist on Paper's real project -- this makes
        // the reinstall attempt fail deterministically regardless of
        // whether this environment actually has network access: offline,
        // PaperMcAdapter.fetchProject() throws on the network call itself;
        // online (as on a real dev machine), fetchProject() succeeds but
        // the version-support check then fails, since no real Minecraft
        // version will ever match "0.0.0-nonexistent". Either way,
        // install() throws before server.jar is ever created, so the
        // already-running guard below is never reached. Relying on "no
        // network in this sandbox" instead (the previous approach) is
        // exactly what made this test flake on a machine with real
        // connectivity.
        val tomlFile = writeServerToml("myserver", defaultToml(minecraftVersion = "0.0.0-nonexistent"))
        val serverDir = tomlFile.parent

        Files.writeString(serverDir.resolve("eula.txt"), "eula=true\n")
        Files.writeString(serverDir.resolve("server.jar"), "not a real jar, just needs to exist")
        // Recorded state is for a different Minecraft version than the
        // toml declares -- this drift must force a reinstall attempt,
        // rather than reaching the already-running guard below.
        ServerStateStore.write(serverDir, InstalledServer(pkg = "paper", minecraftVersion = "1.20.4"))

        val jarletDir = Files.createDirectories(serverDir.resolve(".jarlet"))
        val ownPid = ProcessHandle.current().pid()
        Files.writeString(jarletDir.resolve("server.pid"), "$ownPid\n")

        val result = Jarlet().test(listOf("start", "myserver"))

        assertEquals(1, result.statusCode)
        assertTrue(
            !result.stderr.contains("Server is already running"),
            "expected a reinstall attempt (and its failure) before the already-running guard, got: ${result.stderr}",
        )
    }

    @Test
    fun `a fresh start with no prior server-state still attempts installation`() {
        // No regression: with no server-state.json at all (first-ever
        // start, or a state file that predates this feature), a missing
        // server.jar must still trigger installation exactly as before --
        // it must not be skipped just because there's no recorded state.
        //
        // Uses an unsupported-but-pattern-valid Minecraft version (see the
        // comment on the drift test above) so the install attempt fails
        // deterministically regardless of whether this environment has
        // real network access -- relying on "no network in this sandbox"
        // instead previously made this test spawn a real `java` process
        // (and pass with statusCode 0) on a machine with real connectivity.
        writeServerToml("myserver", defaultToml(minecraftVersion = "0.0.0-nonexistent"))
        val serverDir = serversDir.resolve("myserver")
        Files.writeString(serverDir.resolve("eula.txt"), "eula=true\n")

        val result = Jarlet().test(listOf("start", "myserver"))

        // No live PID is recorded here, so a successful (skipped) install
        // would proceed to actually spawn `java` -- instead, installation
        // is expected to fail deterministically, proving the install step
        // was reached and attempted.
        assertEquals(1, result.statusCode)
        assertTrue(
            !result.stderr.contains("--accept-eula") && !result.stderr.contains("[server."),
            "expected an install-stage failure, not an argument/config-validation failure, got: ${result.stderr}",
        )
    }
}
