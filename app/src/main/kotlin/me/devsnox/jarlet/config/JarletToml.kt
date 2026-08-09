package me.devsnox.jarlet.config

import java.nio.file.Files
import java.nio.file.Path
import org.tomlj.Toml
import org.tomlj.TomlTable

/**
 * Kotlin data model for a `jarlet.toml` server template -- see
 * `src/jarlet.toml` for a worked example and
 * `prototyping/documentation/concepts/server-templating/server-templating.md`
 * for the full field-meaning writeup this mirrors.
 *
 * Read/write goes through [tomlj](https://github.com/tomlj/tomlj) rather
 * than shelling out to `dasel` the way `src/lib/lib.sh`'s
 * `toml_to_json()`/`json_to_toml()` do today -- see [read]/[write] below
 * for what that trade-off means in practice.
 *
 * tomlj is a plain Java TOML parser, not a kotlinx.serialization format
 * module: it hands back an [TomlTable]/`TomlParseResult` object tree that
 * has to be navigated by hand (`getString`, `getTable`, `getArray`, ...),
 * so [read] and [write] map that tree to/from this data model explicitly
 * instead of relying on `@Serializable`/`decodeFromString`.
 *
 * Phase 1 only needs this to round-trip the fields already in
 * `src/jarlet.toml`; fields other adapters (`resolve.sh`/`router.sh`) will
 * eventually need (e.g. richer plugin policy shapes) can be added once
 * those phases port them.
 */
data class JarletToml(
    val template: Template,
    val server: Server,
    val plugins: List<Plugin> = emptyList(),
) {
    data class Template(
        val name: String,
        val description: String? = null,
    )

    data class Server(
        val pkg: String,
        val minecraftVersion: String,
        val memory: String,
        val port: Int,
        val onlineMode: Boolean,
    )

    /**
     * `source`/`id` are the source-agnostic plugin identity pair
     * (`plugin-management.md`'s model); `policy` follows that same doc's
     * three observed shapes -- `{ pin = "<version>" }`,
     * `{ track = "latest" }`, or `{ track = "channel", channel = "..." }`
     * -- collapsed into one nullable-field data class, same as the
     * previous kotlinx.serialization-based model, since tomlj's inline
     * tables don't carry a fixed Kotlin type either and this shape is
     * simplest for callers (see [me.devsnox.jarlet.command.ListCommand]).
     */
    data class Plugin(
        val source: String,
        val id: String,
        val policy: Policy = Policy(),
    ) {
        data class Policy(
            val pin: String? = null,
            val track: String? = null,
            val channel: String? = null,
        )
    }

    companion object {
        /** Reads and parses a `jarlet.toml`-shaped file at [path]. */
        fun read(path: Path): JarletToml {
            val root = Toml.parse(path)
            if (root.hasErrors()) {
                val messages = root.errors().joinToString(System.lineSeparator()) { it.toString() }
                throw IllegalArgumentException("Failed to parse $path as TOML:$messages")
            }

            val templateTable = root.getTable("template")
                ?: throw IllegalArgumentException("$path is missing the [template] table")
            val serverTable = root.getTable("server")
                ?: throw IllegalArgumentException("$path is missing the [server] table")

            val template = Template(
                name = templateTable.getString("name")
                    ?: throw IllegalArgumentException("$path is missing template.name"),
                description = templateTable.getString("description"),
            )

            val server = Server(
                pkg = serverTable.getString("package")
                    ?: throw IllegalArgumentException("$path is missing server.package"),
                minecraftVersion = serverTable.getString("minecraft_version")
                    ?: throw IllegalArgumentException("$path is missing server.minecraft_version"),
                memory = serverTable.getString("memory")
                    ?: throw IllegalArgumentException("$path is missing server.memory"),
                port = serverTable.getLong("port")?.toInt()
                    ?: throw IllegalArgumentException("$path is missing server.port"),
                onlineMode = serverTable.getBoolean("online_mode")
                    ?: throw IllegalArgumentException("$path is missing server.online_mode"),
            )

            val pluginsArray = root.getArray("plugins")
            val plugins = buildList {
                if (pluginsArray != null) {
                    for (i in 0 until pluginsArray.size()) {
                        add(pluginsArray.getTable(i).toPlugin(path))
                    }
                }
            }

            return JarletToml(template = template, server = server, plugins = plugins)
        }

        private fun TomlTable.toPlugin(path: Path): Plugin {
            val source = getString("source")
                ?: throw IllegalArgumentException("$path has a [[plugins]] entry missing source")
            val id = getString("id")
                ?: throw IllegalArgumentException("$path has a [[plugins]] entry missing id")
            val policyTable = getTable("policy")
            val policy = if (policyTable == null) {
                Plugin.Policy()
            } else {
                Plugin.Policy(
                    pin = policyTable.getString("pin"),
                    track = policyTable.getString("track"),
                    channel = policyTable.getString("channel"),
                )
            }
            return Plugin(source = source, id = id, policy = policy)
        }
    }
}

/**
 * Serializes [toml] and writes it to [path], overwriting any existing
 * content.
 *
 * IMPORTANT (mirrors the warning on `json_to_toml()` in `src/lib/lib.sh`):
 * this is a full, lossy rewrite. tomlj has no public API for building or
 * mutating a TOML document in memory -- `Toml.parse` only returns a
 * read-only `TomlParseResult`, and the object types its serializer
 * (`TomlTable.toToml()`) walks (`MutableTomlTable`/`MutableTomlArray`) are
 * package-private, so there's no supported way to construct one from
 * outside `org.tomlj` and hand it to that serializer. Comment/formatting
 * round-tripping was never on the table either way: like ktoml before it
 * and dasel today, tomlj's writer only knows about the data it was given,
 * not source text, so writing back a file read via [JarletToml.read] will
 * drop things like the header comment block in `src/jarlet.toml`.
 * Preserving those is an open question flagged in the migration plan, not
 * solved here. This function therefore renders TOML text directly rather
 * than going through tomlj at all.
 */
fun JarletToml.write(path: Path) {
    val text = buildString {
        appendLine("[template]")
        appendLine("name = ${tomlString(template.name)}")
        template.description?.let { appendLine("description = ${tomlString(it)}") }
        appendLine()

        appendLine("[server]")
        appendLine("package = ${tomlString(server.pkg)}")
        appendLine("minecraft_version = ${tomlString(server.minecraftVersion)}")
        appendLine("memory = ${tomlString(server.memory)}")
        appendLine("port = ${server.port}")
        appendLine("online_mode = ${server.onlineMode}")

        for (plugin in plugins) {
            appendLine()
            appendLine("[[plugins]]")
            appendLine("source = ${tomlString(plugin.source)}")
            appendLine("id = ${tomlString(plugin.id)}")
            appendLine("policy = ${plugin.policy.toInlineToml()}")
        }
    }
    Files.writeString(path, text)
}

/** Renders [value] as a double-quoted, escaped TOML basic string. */
private fun tomlString(value: String): String {
    val escaped = buildString {
        for (c in value) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\t' -> append("\\t")
                '\r' -> append("\\r")
                else -> append(c)
            }
        }
    }
    return "\"$escaped\""
}

/**
 * Renders a [JarletToml.Plugin.Policy] as a TOML inline table, matching
 * the three shapes `src/jarlet.toml` and `plugin-management.md` document:
 * `{ pin = "..." }`, `{ track = "latest" }`, or
 * `{ track = "channel", channel = "..." }`.
 */
private fun JarletToml.Plugin.Policy.toInlineToml(): String {
    val fields = buildList {
        pin?.let { add("pin = ${tomlString(it)}") }
        track?.let { add("track = ${tomlString(it)}") }
        channel?.let { add("channel = ${tomlString(it)}") }
    }
    return "{ ${fields.joinToString(", ")} }"
}
