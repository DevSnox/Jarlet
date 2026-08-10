package me.devsnox.jarlet.config

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Jarlet's own locally-written record of what server software is actually
 * installed at `server.jar`, as opposed to `jarlet.toml`'s
 * [JarletToml.Server] which only records *intent*. Modeled on
 * [PluginStateStore]'s `InstalledVersion`, but deliberately minimal -- the
 * only thing a caller needs to detect drift between "declared" and
 * "installed" is which package and which Minecraft version was last
 * installed.
 */
@Serializable
data class InstalledServer(
    val pkg: String,
    @SerialName("minecraft_version") val minecraftVersion: String,
)

/**
 * Read/write helpers for `server-state.json`, the [InstalledServer]
 * counterpart of [PluginStateStore]'s `plugins-state.json` -- one small
 * JSON file living next to `jarlet.toml` in the server's own directory.
 */
object ServerStateStore {
    private const val STATE_FILENAME = "server-state.json"

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /** Path to `server-state.json` inside [serverDir]. */
    fun stateFile(serverDir: Path): Path = serverDir.resolve(STATE_FILENAME)

    /** The locally-recorded installed server record, or `null` if none exists yet (or the file is unreadable). */
    fun read(serverDir: Path): InstalledServer? {
        val file = stateFile(serverDir)
        if (!Files.isRegularFile(file)) return null
        return json.decodeFromString(Files.readString(file))
    }

    /**
     * Records [entry] as the currently-installed server package/version,
     * replacing whatever was previously recorded. Writes via a
     * temp-file-then-move so a reader never observes a partially-written
     * file, same as [PluginStateStore.writeAll].
     */
    fun write(serverDir: Path, entry: InstalledServer) {
        Files.createDirectories(serverDir)
        val file = stateFile(serverDir)
        val tmp = Files.createTempFile(serverDir, ".$STATE_FILENAME", null)
        try {
            Files.writeString(tmp, json.encodeToString(entry))
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}
