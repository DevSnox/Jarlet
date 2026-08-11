package me.devsnox.jarlet.config

import java.nio.file.Files
import java.nio.file.Path
import org.tomlj.Toml
import org.tomlj.TomlTable

/**
 * Data model for a `jarlet.toml` server template.
 *
 * Read/write goes through [tomlj](https://github.com/tomlj/tomlj) -- see
 * [read]/[write] below for what that means in practice.
 *
 * tomlj is a plain Java TOML parser, not a kotlinx.serialization format
 * module: it hands back an [TomlTable]/`TomlParseResult` object tree that
 * has to be navigated by hand (`getString`, `getTable`, `getArray`, ...),
 * so [read] and [write] map that tree to/from this data model explicitly
 * instead of relying on `@Serializable`/`decodeFromString`.
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
        val policy: Policy = Policy(),
    )

    /**
     * `source`/`id` are the source-agnostic plugin identity pair; `policy`
     * follows one of three shapes -- `{ pin = "<version>" }`,
     * `{ track = "latest" }`, or `{ track = "channel", channel = "..." }`
     * -- collapsed into one nullable-field data class, since tomlj's
     * inline tables don't carry a fixed Kotlin type either and this shape
     * is simplest for callers (see [me.devsnox.jarlet.command.ListCommand]).
     */
    data class Plugin(
        val source: String,
        val id: String,
        val policy: Policy = Policy(),
    )

    /**
     * Version-selection policy shape shared by [Plugin] and [Server] --
     * `{ pin = "<version>" }`, `{ track = "latest" }`, or
     * `{ track = "channel", channel = "..." }` -- collapsed into one
     * nullable-field data class, since tomlj's inline tables don't carry a
     * fixed Kotlin type either and this shape is simplest for callers (see
     * [me.devsnox.jarlet.command.ListCommand]). Not nested under [Plugin]
     * since [Server] needs the same shape too (e.g. pinning a
     * server-software build).
     */
    data class Policy(
        val pin: String? = null,
        val track: String? = null,
        val channel: String? = null,
    )

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
                policy = serverTable.getTable("policy")?.toPolicy() ?: Policy(),
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
            val policy = getTable("policy")?.toPolicy() ?: Policy()
            return Plugin(source = source, id = id, policy = policy)
        }

        /** Shared `pin`/`track`/`channel` parsing, used for both `[plugins.policy]` and `[server.policy]`. */
        private fun TomlTable.toPolicy(): Policy = Policy(
            pin = getString("pin"),
            track = getString("track"),
            channel = getString("channel"),
        )
    }
}

/**
 * Serializes [toml] and writes it to [path], overwriting any existing
 * content.
 *
 * IMPORTANT: this is a full, lossy rewrite. tomlj has no public API for
 * building or mutating a TOML document in memory -- `Toml.parse` only
 * returns a read-only `TomlParseResult`, and the object types its
 * serializer (`TomlTable.toToml()`) walks
 * (`MutableTomlTable`/`MutableTomlArray`) are package-private, so there's
 * no supported way to construct one from outside `org.tomlj` and hand it
 * to that serializer. Comment/formatting round-tripping is not supported
 * either way: tomlj's writer only knows about the data it was given, not
 * source text, so writing back a file read via [JarletToml.read] drops
 * things like header comment blocks. This function therefore renders TOML
 * text directly rather than going through tomlj at all.
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
        if (server.policy != JarletToml.Policy()) {
            appendLine("policy = ${server.policy.toInlineToml()}")
        }

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
 * Renders a [JarletToml.Policy] as a TOML inline table: `{ pin = "..." }`,
 * `{ track = "latest" }`, or `{ track = "channel", channel = "..." }`.
 */
private fun JarletToml.Policy.toInlineToml(): String {
    val fields = buildList {
        pin?.let { add("pin = ${tomlString(it)}") }
        track?.let { add("track = ${tomlString(it)}") }
        channel?.let { add("channel = ${tomlString(it)}") }
    }
    return "{ ${fields.joinToString(", ")} }"
}
