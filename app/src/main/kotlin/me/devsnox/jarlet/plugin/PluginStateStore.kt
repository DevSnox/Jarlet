package me.devsnox.jarlet.plugin

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * One entry of `plugins-state.json` -- Jarlet's own locally-written record
 * of what's actually installed for a given declared `(source, id)` pair,
 * as opposed to `jarlet.toml`'s [me.devsnox.jarlet.config.JarletToml.Plugin]
 * which only records *intent*. Kotlin equivalent of the object shape
 * `write_installed_version()` in `src/plugin/store.sh` persists (see e.g.
 * `src/adapter/plugin/hangar.sh`'s call to it for a concrete example of
 * every field being populated).
 *
 * [source]/[id] are included on every entry (not just used as a lookup
 * key) because the store is a flat JSON array, not a map -- same shape
 * `read_all_installed()` returns to `list.sh` today.
 */
@Serializable
data class InstalledVersion(
    val source: String,
    val id: String,
    @SerialName("version_name") val versionName: String,
    @SerialName("version_id") val versionId: Long? = null,
    @SerialName("channel_name") val channelName: String? = null,
    val sha256: String? = null,
    val size: Long? = null,
    val file: String,
    val external: Boolean = false,
)

/**
 * Kotlin port of `src/plugin/store.sh`'s `plugins-state.json`
 * read/write/remove helpers. Pure local bookkeeping, same as the bash
 * version -- has no knowledge of any plugin source; that's
 * [PluginRouter]/[AdapterRegistry]'s job.
 *
 * Unlike `store.sh`, this does not also own `jarlet.toml` rewriting
 * (`write_toml_file()`/`json_to_toml()` there) -- that belongs with
 * add/remove (phase 5), which is out of scope here.
 */
object PluginStateStore {
    private const val STATE_FILENAME = "plugins-state.json"

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /** Kotlin equivalent of `plugin_state_file()`. */
    fun stateFile(serverDir: Path): Path = serverDir.resolve(STATE_FILENAME)

    /**
     * The whole `plugins-state.json` array, or an empty list if no state
     * file exists yet for this server. Kotlin equivalent of
     * `read_all_installed()`.
     */
    fun readAll(serverDir: Path): List<InstalledVersion> {
        val file = stateFile(serverDir)
        if (!Files.isRegularFile(file)) return emptyList()
        return json.decodeFromString(Files.readString(file))
    }

    /**
     * The locally-recorded installed entry for `source`+`id`, or `null` if
     * none is recorded. Combines `read_installed_version()` and
     * `read_installed_file()` from the bash version, since both are just
     * different fields of the same record here.
     */
    fun read(serverDir: Path, source: String, id: String): InstalledVersion? =
        readAll(serverDir).firstOrNull { it.source == source && it.id == id }

    /**
     * Records/replaces the installed-version entry for `entry.source`+
     * `entry.id`. Kotlin equivalent of `write_installed_version()`.
     */
    fun write(serverDir: Path, entry: InstalledVersion) {
        val updated = readAll(serverDir).filterNot { it.source == entry.source && it.id == entry.id } + entry
        writeAll(serverDir, updated)
    }

    /**
     * Drops the `source`+`id` entry entirely, if one exists. A no-op if no
     * state file exists yet, or no matching entry is found. Kotlin
     * equivalent of `remove_installed_version()`.
     */
    fun remove(serverDir: Path, source: String, id: String) {
        val existing = readAll(serverDir)
        val updated = existing.filterNot { it.source == source && it.id == id }
        if (updated.size != existing.size) writeAll(serverDir, updated)
    }

    /**
     * Writes [entries] to `plugins-state.json` via a temp-file-then-move,
     * mirroring `write_installed_version()`/`remove_installed_version()`'s
     * `mktemp` + `mv` (and its `trap ... RETURN` cleanup) so a reader never
     * observes a partially-written file.
     */
    private fun writeAll(serverDir: Path, entries: List<InstalledVersion>) {
        Files.createDirectories(serverDir)
        val file = stateFile(serverDir)
        val tmp = Files.createTempFile(serverDir, ".$STATE_FILENAME", null)
        try {
            Files.writeString(tmp, json.encodeToString(entries))
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}
