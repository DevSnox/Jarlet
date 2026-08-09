package me.devsnox.jarlet.config

import java.nio.file.Files
import java.nio.file.Path

/**
 * Thrown for the same failure cases `src/lib/lib.sh`'s `fail()` covers
 * when called from `sys_config_value()`: a missing config file, or a
 * missing/empty required key.
 */
class SysConfigException(message: String) : Exception(message)

/**
 * Kotlin equivalent of `src/lib/lib.sh`'s `sys_config_value()`: reads
 * `key=value` pairs out of a `jarlet-sys.conf`-shaped file.
 *
 * The file format itself is unchanged from the bash prototype (flat,
 * awk-parseable `KEY=value`, see `src/jarlet-sys.conf`) -- only the
 * reading code is ported here.
 *
 * [default] loads the copy bundled with this build
 * (`app/src/main/resources/jarlet-sys.conf`, mirrored verbatim
 * from `src/jarlet-sys.conf`) so callers don't need to know a real
 * filesystem location for what is, today, a fixed set of system-level
 * constants. [fromFile] remains available for an explicit path -- tests,
 * or an eventual user override -- once one is needed.
 */
class SysConfig private constructor(
    private val values: Map<String, String>,
    private val source: String,
) {

    /** Returns the value for [key], failing the same way `sys_config_value()` does if it's missing or empty. */
    fun value(key: String): String =
        values[key]?.takeIf { it.isNotEmpty() }
            ?: throw SysConfigException("Missing required key '$key' in $source")

    companion object {
        private const val DEFAULT_RESOURCE_PATH = "/jarlet-sys.conf"

        /** Loads the `jarlet-sys.conf` bundled with this build. */
        fun default(): SysConfig {
            val text = SysConfig::class.java.getResourceAsStream(DEFAULT_RESOURCE_PATH)
                ?.bufferedReader()
                ?.readText()
                ?: throw SysConfigException("Bundled resource $DEFAULT_RESOURCE_PATH is missing")

            return SysConfig(KeyValueFormat.parse(text.lines()), "bundled resource $DEFAULT_RESOURCE_PATH")
        }

        /** Loads a `jarlet-sys.conf`-shaped file from an explicit filesystem [path]. */
        fun fromFile(path: Path): SysConfig {
            if (!Files.isRegularFile(path)) {
                throw SysConfigException("$path does not exist")
            }
            return SysConfig(KeyValueFormat.parse(Files.readAllLines(path)), path.toString())
        }
    }
}
