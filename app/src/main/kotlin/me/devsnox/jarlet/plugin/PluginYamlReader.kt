package me.devsnox.jarlet.plugin

import java.io.IOException
import java.nio.file.Path
import java.util.zip.ZipFile
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.exceptions.YamlEngineException

/**
 * `name`/`version`/`main`, plus `depend`/`softdepend`, extracted from a
 * plugin jar's bundled `plugin.yml`, per
 * `prototyping/documentation/sources/plugin-yml-format.md`. Still
 * deliberately excludes every other documented field (`commands`,
 * `permissions`, `libraries`, ...) -- none of it feeds any of Jarlet's
 * current uses, so parsing it would be pure unused surface.
 *
 * `name`/`version`/`main` keep the original "all three required, or the
 * whole read is `null`" treatment: they're all genuinely required by the
 * `plugin.yml` schema itself (see [PluginYamlReader.read]'s doc comment for
 * the full "fail closed" reasoning), so a jar missing one isn't a
 * legitimate plugin.yml at all.
 *
 * `depend`/`softdepend`, by contrast, are genuinely optional per the
 * schema -- their absence is the normal, non-error case, not a sign of a
 * malformed file. They therefore do NOT participate in the "required or
 * null" gate above: they're additive data attached to an otherwise-valid
 * read, always defaulting to an empty list rather than ever causing
 * [PluginYamlReader.read] to return `null`.
 */
data class PluginYamlInfo(
    val name: String,
    val version: String,
    val main: String,
    val depend: List<String> = emptyList(),
    val softdepend: List<String> = emptyList(),
)

/**
 * Reads the `plugin.yml` entry bundled at the root of a plugin jar's zip
 * archive and extracts [PluginYamlInfo] from it, per
 * `prototyping/documentation/sources/plugin-yml-format.md`. The name/
 * version/main portion is a *leading indicator* only -- existing filename/
 * adapter-based naming remains the fallback and is not replaced by it. The
 * `depend`/`softdepend` portion is wired into [me.devsnox.jarlet.command.AddCommand]
 * and `me.devsnox.jarlet.command.UpdateCommand`'s post-install dependency
 * check/`--resolve-dependencies` step.
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
        } catch (e: LinkageError) {
            // Same "never throws" reasoning as the LinkageError catch below
            // around the parse step: readEntry() also goes through JDK zip/
            // inflate machinery (ZipFile, entry input streams) that can hit
            // its own class-initialization/native-linkage gaps under
            // GraalVM native-image (e.g. a missing zlib symbol registration),
            // independently of the SnakeYAML-specific UTF-32BE case this
            // function was originally hardened against. Left uncaught here,
            // that would still break this function's documented "never
            // throws" contract just the same, so it gets the identical
            // narrow LinkageError treatment rather than leaving this first
            // try block as the one remaining gap.
            return null
        }

        val parsed = try {
            // Decode to a String and use loadFromString rather than
            // loadFromInputStream: the latter wraps the stream in
            // snakeyaml-engine's YamlUnicodeReader for BOM-based encoding
            // auto-detection, whose static initializer eagerly builds a
            // charset table including UTF-32BE -- a charset GraalVM
            // native-image doesn't register by default, so merely loading
            // that class throws ExceptionInInitializerError on the native
            // binary, regardless of the actual file's encoding/content.
            // plugin.yml is conventionally plain ASCII/UTF-8 (Bukkit/Spigot
            // ecosystem), so decoding it ourselves up front sidesteps the
            // auto-detection machinery -- and the exotic-charset support --
            // entirely.
            Load(LoadSettings.builder().build()).loadFromString(String(yamlBytes, Charsets.UTF_8))
        } catch (e: YamlEngineException) {
            return null
        } catch (e: RuntimeException) {
            // The engine wraps some malformed-input cases (e.g. duplicate
            // keys, marker errors) in plain RuntimeException/ClassCastException
            // rather than YamlEngineException -- treat any of those as
            // "unparseable" too, same as a missing/corrupt entry.
            return null
        } catch (e: LinkageError) {
            // Belt-and-suspenders alongside the loadFromString switch above:
            // an Error (e.g. ExceptionInInitializerError/NoClassDefFoundError
            // from a class the engine touches failing to initialize, as
            // YamlUnicodeReader's UTF-32BE registration once did under
            // GraalVM native-image) is not a subtype of Exception, so it
            // would otherwise sail past the catches above and break this
            // function's documented "never throws" contract. Caught
            // narrowly as LinkageError rather than Throwable: that's the
            // real Java hierarchy for "class failed to load/link", and
            // stays clear of masking unrelated fatal errors (e.g.
            // OutOfMemoryError) that callers should NOT see silently
            // swallowed into a null.
            return null
        }

        val map = parsed as? Map<*, *> ?: return null

        val name = (map["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val main = (map["main"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val version = resolvedVersion(map["version"] as? String) ?: return null

        return PluginYamlInfo(
            name = name,
            version = version,
            main = main,
            depend = stringListOrEmpty(map["depend"]),
            softdepend = stringListOrEmpty(map["softdepend"]),
        )
    }

    /**
     * Parses a `depend`/`softdepend` value as a list of non-blank strings,
     * defensively: since these fields never gate whether [read] "succeeded"
     * at all (see [PluginYamlInfo]'s doc comment), any shape other than a
     * proper YAML list of strings -- wrong type entirely, or a list with
     * non-string entries -- is treated as an empty list rather than failing
     * the whole read.
     */
    private fun stringListOrEmpty(raw: Any?): List<String> {
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { (it as? String)?.trim()?.takeIf { s -> s.isNotEmpty() } }
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
