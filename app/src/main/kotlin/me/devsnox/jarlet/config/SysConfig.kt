package me.devsnox.jarlet.config

import java.nio.file.Files
import java.nio.file.Path

/** Thrown for a missing config file, or a missing/empty required key. */
class SysConfigException(message: String) : Exception(message)

/**
 * Reads `key=value` pairs out of a `jarlet-sys.conf`-shaped file.
 *
 * [default] loads the copy bundled with this build
 * (`app/src/main/resources/jarlet-sys.conf`) so callers don't need to know
 * a real filesystem location for what is a fixed set of system-level
 * constants. [fromFile] remains available for an explicit path -- tests,
 * or an eventual user override -- once one is needed.
 */
class SysConfig private constructor(
    private val values: Map<String, String>,
    private val source: String,
) {

    /** Returns the value for [key], throwing if it's missing or empty. */
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
