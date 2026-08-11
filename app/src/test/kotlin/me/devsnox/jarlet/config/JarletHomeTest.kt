package me.devsnox.jarlet.config

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The JVM has no supported way to mutate an already-running process's
 * environment (see [me.devsnox.jarlet.server.ServerPaths]'s
 * `SERVERS_DIR_PROPERTY` doc comment for the same constraint), so only the
 * no-override fallback path is exercised here.
 */
class JarletHomeTest {

    @Test
    fun `resolves to $home slash jarlet when JARLET_HOME is not set`() {
        if (System.getenv("JARLET_HOME").isNullOrEmpty()) {
            assertEquals(Paths.get(System.getProperty("user.home"), "jarlet"), JarletHome.resolve())
        }
        // else: JARLET_HOME is set in this environment and can't be
        // unset in-process, so the fallback path can't be exercised here.
    }
}
