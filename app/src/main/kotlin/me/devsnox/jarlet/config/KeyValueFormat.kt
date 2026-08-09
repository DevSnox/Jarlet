package me.devsnox.jarlet.config

/**
 * Parses the simple `key=value` line format shared by `jarlet-sys.conf`
 * and the bundled `VERSION` resource.
 *
 * Mirrors the awk one-liner both `src/lib/lib.sh`'s `config_value()` and
 * `src/lib/version.sh`'s `version_value()` use: a line whose first
 * non-blank character is `#` is a comment, the first `=` on a line splits
 * key from value, and the first match for a given key wins (later
 * duplicate keys are ignored).
 */
internal object KeyValueFormat {
    fun parse(lines: List<String>): Map<String, String> {
        val values = LinkedHashMap<String, String>()
        for (line in lines) {
            if (line.trimStart().startsWith("#")) continue

            val separator = line.indexOf('=')
            if (separator <= 0) continue

            val key = line.substring(0, separator)
            values.putIfAbsent(key, line.substring(separator + 1))
        }
        return values
    }
}
