package me.devsnox.jarlet.plugin

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import java.nio.file.Files
import me.devsnox.jarlet.config.JarletToml
import me.devsnox.jarlet.config.write
import me.devsnox.jarlet.server.ServerCommandException
import me.devsnox.jarlet.server.ServerPaths
import me.devsnox.jarlet.server.serverCommandBody

/**
 * `jarlet plugin add <name> <identifier> [--pin <version> | --channel <name>] [--source <hangar|spiget|github-releases>] [--trust]`
 * -- Kotlin port of `src/plugin/commands.sh`'s `cmd_add()`.
 *
 * `source` is no longer a positional argument -- per the alpha.5 redesign
 * `commands.sh` documents, it's inferred from `identifier` by
 * [SourceResolver.resolveAddIdentifier] (`owner/repo` -> github-releases,
 * numeric -> probe hangar/spiget, name -> hangar exact slug then spiget
 * exact-name search), unless `--source` is given as an explicit escape
 * hatch that skips inference entirely (still validated via
 * [SourceResolver.validateSourceIdShape]).
 *
 * Declares a new `[[plugins]]` entry in `jarlet.toml` (a full rewrite via
 * [me.devsnox.jarlet.config.write], same lossy-rewrite tradeoff
 * `write_toml_file()`/`json_to_toml()` document in the bash version) and
 * only *then* routes it through [PluginRouter.route] to actually fetch it
 * -- matching `cmd_add()`'s documented "declare, then act" order exactly:
 * a failed fetch still leaves the plugin declared in `jarlet.toml`.
 *
 * ## Integration status
 *
 * Fully wired against what exists in this tree as of this port:
 * [SourceResolver] (HTTP-based Hangar/Spiget probing, no adapter
 * dependency), [JarletToml] (read/mutate/write -- its own `ktoml` vs.
 * `tomlj` backend churn resolved concurrently with this phase, so `add`'s
 * declare-then-fetch flow should genuinely round-trip `jarlet.toml`
 * today), and [PluginRouter.route] (phase 3, already landed).
 * [PluginRouter.route] itself is a real, working router -- but
 * [AdapterRegistry] has, as of this port, zero adapters registered
 * (`GithubReleasesAdapter` exists in the tree but isn't wired into
 * [AdapterRegistry]'s map yet; `HangarAdapter`/`SpigetAdapter` don't exist
 * yet at all), so today every `add` will declare successfully in
 * `jarlet.toml` and then print `Skipping "<id>" (<source>): no adapter is
 * implemented for this source` instead of actually fetching a jar --
 * exactly the router's documented, non-error behavior for an unregistered
 * source, not a bug in this command.
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
        help = "Skip source inference; must be one of hangar, spiget, github-releases.",
    )
    private val trust by option("--trust", help = "Proceed past an external-hosting gate this adapter can't otherwise resolve.")
        .flag(default = false)

    override fun run() = serverCommandBody {
        if (pin != null && channel != null) {
            throw ServerCommandException("--pin and --channel are mutually exclusive")
        }

        val serverDir = ServerPaths.serverDir(name)
        if (!Files.isDirectory(serverDir)) {
            throw ServerCommandException("No server named '$name' found at $serverDir")
        }

        val tomlFile = serverDir.resolve(ServerPaths.templateFilename())
        if (!Files.isRegularFile(tomlFile)) {
            throw ServerCommandException("$tomlFile does not exist. Run setup (or start) for '$name' first to generate it.")
        }

        val toml = try {
            JarletToml.read(tomlFile)
        } catch (e: Exception) {
            throw ServerCommandException("Could not parse $tomlFile as TOML: ${e.message}")
        }

        val pluginsDir = serverDir.resolve("plugins")
        Files.createDirectories(pluginsDir)

        val (source, id) = if (sourceOverride != null) {
            SourceResolver.validateSourceIdShape(sourceOverride!!, identifier)
            sourceOverride!! to identifier
        } else {
            val resolved = SourceResolver.resolveAddIdentifier(identifier, warn = { echo(it, err = true) })
            echo("""Resolved "$identifier" to ${resolved.id} (${resolved.source})""")
            resolved.source to resolved.id
        }

        SourceResolver.checkIdAvailable(toml, id)

        // With neither --pin nor --channel given, default to tracking the
        // "Release" channel -- matching hangar.sh's own internal default
        // (`.channel // "Release"`) for entries that omit one.
        val policy = if (pin != null) {
            JarletToml.Plugin.Policy(pin = pin)
        } else {
            JarletToml.Plugin.Policy(track = "channel", channel = channel ?: "Release")
        }

        val updatedToml = toml.copy(plugins = toml.plugins + JarletToml.Plugin(source = source, id = id, policy = policy))

        echo("Note: this rewrites $tomlFile in full; hand-written comments and formatting are not preserved.")
        updatedToml.write(tomlFile)
        echo("""Declared "$id" ($source) in $tomlFile""")

        PluginRouter.route(serverDir, pluginsDir, source, id, policy, trust, echo = { echo(it) })
    }
}
