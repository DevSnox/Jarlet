package me.devsnox.jarlet.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import me.devsnox.jarlet.Log
import me.devsnox.jarlet.command.lib.resolvePluginCommandContext
import java.nio.file.Files
import me.devsnox.jarlet.config.JarletToml
import me.devsnox.jarlet.command.lib.ServerCommandException
import me.devsnox.jarlet.command.lib.serverCommandBody
import me.devsnox.jarlet.plugin.PluginDeclarer
import me.devsnox.jarlet.plugin.PluginDependencyChecker

/**
 * `jarlet plugin add <name> <identifier> [--pin <version> | --channel <name>] [--source <hangar|spiget|github>] [--trust]`
 * -- Kotlin port of `src/plugin/commands.sh`'s `cmd_add()`.
 *
 * `source` is no longer a positional argument -- per the alpha.5 redesign
 * `commands.sh` documents, it's inferred from `identifier` by
 * [me.devsnox.jarlet.plugin.SourceResolver.resolveAddIdentifier] (`owner/repo` -> github,
 * numeric -> probe hangar/spiget, name -> hangar exact slug then spiget
 * exact-name search), unless `--source` is given as an explicit escape
 * hatch that skips inference entirely (still validated via
 * [me.devsnox.jarlet.plugin.SourceResolver.validateSourceIdShape]).
 *
 * Declares a new `[[plugins]]` entry in `jarlet.toml` (a full rewrite, same
 * lossy-rewrite tradeoff `write_toml_file()`/`json_to_toml()` document in
 * the bash version) and only *then* routes it to actually fetch it --
 * matching `cmd_add()`'s documented "declare, then act" order exactly: a
 * failed fetch still leaves the plugin declared in `jarlet.toml`. The
 * resolve/declare/write/route sequence itself is shared with
 * [PluginDependencyChecker] via [PluginDeclarer.declareAndRoute].
 *
 * Fully wired end-to-end: [me.devsnox.jarlet.plugin.SourceResolver], [JarletToml] (tomlj-backed
 * read/mutate/write), and [PluginDeclarer.declareAndRoute] against
 * [me.devsnox.jarlet.plugin.AdapterRegistry]'s three registered adapters (hangar, github, spiget).
 */
class AddCommand : CliktCommand(name = "add") {

    override fun help(context: Context) = "Declare and fetch a new plugin for a server."

    private val name by argument(name = "name", help = "Server name (a directory under the servers root).")
    private val identifier by argument(
        name = "identifier",
        help = "Hangar/Spiget slug or numeric id, an 'owner/repo' GitHub identifier, or (with --source) a raw source id.",
    )

    private val pin by option("--pin", help = "Pin to an exact version, instead of tracking a channel.")
    private val channel by option("--channel", help = "Track this release channel (default: Release).")
    private val sourceOverride by option(
        "--source",
        help = "Skip source inference; must be one of hangar, spiget, github.",
    )
    private val trust by option("--trust", help = "Proceed past an external-hosting gate this adapter can't otherwise resolve.")
        .flag(default = false)
    private val resolveDependencies by option(
        "--resolve-dependencies",
        help = "Automatically resolve and add this plugin's plugin.yml \"depend\" entries that aren't already declared.",
    ).flag(default = false)

    override fun run() = serverCommandBody {
        if (pin != null && channel != null) {
            throw ServerCommandException("--pin and --channel are mutually exclusive")
        }

        val (serverDir, tomlFile, toml) = resolvePluginCommandContext(name)

        val pluginsDir = serverDir.resolve("plugins")
        Files.createDirectories(pluginsDir)

        // With neither --pin nor --channel given, default to tracking the
        // "Release" channel -- matching hangar.sh's own internal default
        // (`.channel // "Release"`) for entries that omit one.
        val policy = if (pin != null) {
            JarletToml.Plugin.Policy(pin = pin)
        } else {
            JarletToml.Plugin.Policy(track = "channel", channel = channel ?: "Release")
        }

        val declaration = PluginDeclarer.declareAndRoute(
            serverDir, pluginsDir, tomlFile, toml, identifier, sourceOverride, policy, trust,
        )
        Log.info("""Declared "${declaration.id}" (${declaration.source}) in $tomlFile""")

        PluginDependencyChecker.checkAndResolve(
            serverDir, pluginsDir, tomlFile, declaration.toml, declaration.source, declaration.id, resolveDependencies, trust,
        )
    }
}
