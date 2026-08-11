package me.devsnox.jarlet.config

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.devsnox.jarlet.Log

/**
 * One entry of `plugins-state.json` -- Jarlet's own locally-written record
 * of what's actually installed for a given declared `(source, id)` pair,
 * as opposed to `jarlet.toml`'s [JarletToml.Plugin] which only records
 * *intent*.
 *
 * [source]/[id] are included on every entry (not just used as a lookup
 * key) because the store is a flat JSON array, not a map.
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
    /**
     * Human-readable resource name, cached at install/update time for
     * sources whose declared [id] is not itself human-readable (Spiget's
     * `id` is a bare numeric resource id -- unlike Hangar/GitHub-releases,
     * whose declared id already IS a readable slug/name, so they never
     * populate this). Null for every other source, and null for Spiget
     * entries written before this field existed. See
     * [me.devsnox.jarlet.command.ListCommand]'s use of it to avoid
     * `jarlet plugin list` showing raw numeric ids with no indication of
     * what the plugin actually is.
     */
    @SerialName("display_name") val displayName: String? = null,
)

/**
 * Read/write/remove helpers for `plugins-state.json`. Pure local
 * bookkeeping -- has no knowledge of any plugin source; that's
 * [me.devsnox.jarlet.plugin.PluginRouter]/[me.devsnox.jarlet.plugin.AdapterRegistry]'s job.
 * `jarlet.toml` rewriting is handled separately by the add/remove/update
 * commands, out of scope here.
 */
object PluginStateStore {
    private const val STATE_FILENAME = "plugins-state.json"

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /** Path to `plugins-state.json` inside [serverDir]. */
    fun stateFile(serverDir: Path): Path = serverDir.resolve(STATE_FILENAME)

    /**
     * The whole `plugins-state.json` array, or an empty list if no state
     * file exists yet for this server.
     */
    fun readAll(serverDir: Path): List<InstalledVersion> {
        val file = stateFile(serverDir)
        if (!Files.isRegularFile(file)) return emptyList()
        return json.decodeFromString(Files.readString(file))
    }

    /**
     * The locally-recorded installed entry for `source`+`id`, or `null` if
     * none is recorded.
     */
    fun read(serverDir: Path, source: String, id: String): InstalledVersion? =
        readAll(serverDir).firstOrNull { it.source == source && it.id == id }

    /** Records/replaces the installed-version entry for `entry.source`+`entry.id`. */
    fun write(serverDir: Path, entry: InstalledVersion) {
        val updated = readAll(serverDir).filterNot { it.source == entry.source && it.id == entry.id } + entry
        writeAll(serverDir, updated)
    }

    /**
     * Drops the `source`+`id` entry entirely, if one exists. A no-op if no
     * state file exists yet, or no matching entry is found.
     */
    fun remove(serverDir: Path, source: String, id: String) {
        val existing = readAll(serverDir)
        val updated = existing.filterNot { it.source == source && it.id == id }
        if (updated.size != existing.size) writeAll(serverDir, updated)
    }

    /**
     * Deletes the previously-installed jar recorded for `source`+`id` when
     * an update resolved a *different* filename for the new version --
     * guards against a version bump that changes the download's filename
     * (embedded version numbers, e.g. `worldedit-bukkit-7.4.4.jar` ->
     * `worldedit-bukkit-7.4.5.jar`, or a wholesale naming-convention switch,
     * e.g. Hangar's Geyser project resolving `Geyser.jar` on one version and
     * `Geyser-Spigot.jar` on another via its external-hosting path) leaving
     * the stale jar sitting in [pluginsDir] forever, since a plain
     * `Files.move`/copy of the new file never touches an old file under a
     * different name. Two jars that both declare the same plugin `name` in
     * their `plugin.yml` is exactly the duplicate-plugin scenario Paper
     * cannot be trusted to resolve safely.
     *
     * Callers MUST invoke this only after the new version has already been
     * downloaded, verified, and moved into place in [pluginsDir] -- so that
     * a failed/interrupted download never leaves the plugin with zero
     * working jars; deleting the stale file after the working replacement
     * is already on disk is what makes that ordering safe. Call this
     * *before* [write] records the new entry, since [write] overwrites the
     * very [InstalledVersion.file] this needs to read to know what to
     * delete.
     *
     * Never throws: a failure to remove a stale jar (e.g. a permissions
     * issue) must not turn an otherwise fully-successful update into a
     * reported failure -- it's logged as a warning instead.
     */
    fun deleteStaleFile(serverDir: Path, pluginsDir: Path, source: String, id: String, newFile: String) {
        val previousFile = read(serverDir, source, id)?.file ?: return
        if (previousFile == newFile) return

        try {
            if (Files.deleteIfExists(pluginsDir.resolve(previousFile))) {
                Log.info("Removed stale jar from previous version: $previousFile")
            }
        } catch (e: Exception) {
            Log.warn("Could not remove stale jar '$previousFile' left over from a previous version of \"$id\" ($source): ${e.message}")
        }
    }

    /**
     * Writes [entries] to `plugins-state.json` via a temp-file-then-move
     * so a reader never observes a partially-written file.
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
