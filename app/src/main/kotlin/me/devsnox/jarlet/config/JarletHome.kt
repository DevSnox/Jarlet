package me.devsnox.jarlet.config

import java.nio.file.Path
import java.nio.file.Paths

/** Resolves Jarlet's home directory: `$JARLET_HOME` if set, else `~/jarlet`. Shared by every store that persists global (non-per-server) state. */
object JarletHome {
    fun resolve(): Path {
        val home = System.getenv("JARLET_HOME")?.takeIf { it.isNotEmpty() }
            ?: Paths.get(System.getProperty("user.home"), "jarlet").toString()
        return Paths.get(home)
    }
}
