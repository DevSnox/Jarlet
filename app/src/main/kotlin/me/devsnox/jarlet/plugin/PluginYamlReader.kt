package me.devsnox.jarlet.plugin

import java.io.IOException
import java.nio.file.Path
import java.util.zip.ZipFile
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.exceptions.YamlEngineException

/**
 * Minimal `name`/`version`/`main` extracted from a plugin jar's bundled
 * `plugin.yml`, per the "minimal extraction set" settled in
 * `prototyping/documentation/sources/plugin-yml-format.md`. Deliberately
 * excludes every other documented field (`depend`, `commands`,
 * `permissions`, `libraries`, ...) -- none of it feeds Jarlet's "better
 * name/version as a leading indicator" purpose, so parsing it would be
 * pure unused surface.
 *
 * All three fields are non-null here by construction: [PluginYamlReader.read]
 * only ever returns a non-null [PluginYamlInfo] when all three were present
 * and usable (see its doc comment for why "require all three" beats
 * "return partial/nullable data" for this use case).
 */
data class PluginYamlInfo(
    val name: String,
    val version: String,
    val main: String,
)

/**
 * Reads the `plugin.yml` entry bundled at the root of a plugin jar's zip
 * archive and extracts [PluginYamlInfo] from it, per
 * `prototyping/documentation/sources/plugin-yml-format.md`. This is a
 * *leading indicator* only -- existing filename/adapter-based naming
 * remains the fallback and is not replaced by this. Nothing here is wired
 * into any adapter/command yet; that is a separate, later step.
 */
object PluginYamlReader {
    private const val ENTRY_NAME = "plugin.yml"

    /**
     * Extracts [PluginYamlInfo] from the `plugin.yml` entry of the jar at
     * [jarPath], or `null` if extraction wasn't possible for any reason --
     * the jar/entry doesn't exist, the zip is corrupt, the YAML is
     * malformed, a required key is missing, or a value has the wrong
     * type. Never throws: this is a best-effort leading indicator (a
     * Paper-plugin-only jar with no legacy `plugin.yml`, or a non-plugin
     * jar entirely, is an expected, non-exceptional input), so callers
     * never need a try/catch around calling it.
     *
     * Design choice -- **all three fields required, or `null`**, rather
     * than returning a partial result with nullable fields: `name`,
     * `version`, and `main` are all genuinely required by the
     * `plugin.yml` schema itself (a server refuses to load a plugin
     * missing any of them), so a jar missing one isn't a legitimate
     * plugin.yml at all -- it's either not a real plugin, or itself
     * malformed/unreliable, and per the research doc's "fail closed"
     * framing that's exactly the case where this leading indicator
     * should defer entirely to the filename/adapter fallback rather than
     * hand back a half-populated, partially-trustworthy result a caller
     * would have to null-check field-by-field anyway.
     */
    fun read(jarPath: Path): PluginYamlInfo? {
        val yamlBytes = try {
            readEntry(jarPath) ?: return null
        } catch (e: IOException) {
            return null
        }

        val parsed = try {
            Load(LoadSettings.builder().build()).loadFromInputStream(yamlBytes.inputStream())
        } catch (e: YamlEngineException) {
            return null
        } catch (e: RuntimeException) {
            // The engine wraps some malformed-input cases (e.g. duplicate
            // keys, marker errors) in plain RuntimeException/ClassCastException
            // rather than YamlEngineException -- treat any of those as
            // "unparseable" too, same as a missing/corrupt entry.
            return null
        }

        val map = parsed as? Map<*, *> ?: return null

        val name = (map["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val main = (map["main"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val version = resolvedVersion(map["version"] as? String) ?: return null

        return PluginYamlInfo(name = name, version = version, main = main)
    }

    /**
     * Treats a `version` string containing an unresolved build
     * placeholder (`${project.version}`, `${version}`, etc.) as absent
     * rather than passing the literal placeholder through -- per the
     * research doc, this is a real, observed failure mode of plugin
     * build pipelines that forgot to configure resource filtering.
     */
    private fun resolvedVersion(raw: String?): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (trimmed.contains("\${")) return null
        return trimmed
    }

    /** Reads the raw bytes of the `plugin.yml` entry from [jarPath]'s zip archive, or `null` if the jar or entry doesn't exist. */
    private fun readEntry(jarPath: Path): ByteArray? {
        if (!java.nio.file.Files.isRegularFile(jarPath)) return null
        ZipFile(jarPath.toFile()).use { zip ->
            val entry = zip.getEntry(ENTRY_NAME) ?: return null
            return zip.getInputStream(entry).use { it.readBytes() }
        }
    }
}
