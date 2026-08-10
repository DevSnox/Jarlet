package me.devsnox.jarlet.plugin

import java.nio.file.Path
import me.devsnox.jarlet.Log
import me.devsnox.jarlet.config.JarletToml
import me.devsnox.jarlet.config.write

/**
 * The shared "resolve, declare, fetch" sequence [me.devsnox.jarlet.command.AddCommand]
 * and [PluginDependencyChecker] each need to add exactly one `[[plugins]]`
 * entry to a `jarlet.toml` and then fetch it -- extracted per
 * `plugin-package-architecture-review.md` finding 1.2, which found both
 * callers had independently hand-rolled the same six-step sequence (resolve
 * -> [SourceResolver.checkIdAvailable] -> build a policy -> append the entry
 * -> write the toml -> [PluginRouter.route]), and that the drift between the
 * two copies had already caused one real bug: only [me.devsnox.jarlet.command.AddCommand]
 * printed the "rewrites in full" note before writing, so `add
 * --resolve-dependencies` only showed that warning once instead of once per
 * dependency actually declared.
 *
 * Policy-*building* is deliberately kept out of this function and left to
 * each caller (per the review's Option C): [me.devsnox.jarlet.command.AddCommand]
 * supports `--pin`/`--channel` overrides with its own validation,
 * [PluginDependencyChecker] always uses a fixed channel/Release policy, and
 * parameterizing a "policy-building strategy" here would be more machinery
 * than just having each caller build its own [JarletToml.Policy]
 * and pass the finished value in.
 */
object PluginDeclarer {

    /** The outcome of [declareAndRoute]: the resolved `(source, id)` pair and the toml as written to disk. */
    data class Declaration(val toml: JarletToml, val source: String, val id: String)

    /**
     * Resolves [identifier] to a `(source, id)` pair -- via
     * [sourceOverride]/[SourceResolver.validateSourceIdShape] if given,
     * otherwise via [SourceResolver.resolveAddIdentifier] -- checks it isn't
     * already declared in [toml], appends a new [JarletToml.Plugin] entry
     * using the caller-supplied [policy], rewrites [tomlFile] in full (with
     * the accompanying "hand-written comments and formatting are not
     * preserved" note logged immediately before, so both callers now show
     * it consistently), and finally routes the fetch via [PluginRouter.route].
     *
     * Returns the resolved `(source, id)` and the updated [JarletToml] so
     * the caller can log its own final "Declared ..." message (the two
     * callers' wording differs -- [PluginDependencyChecker] mentions which
     * plugin the entry was resolved as a dependency of) and thread the
     * growing plugin list into any further work.
     */
    fun declareAndRoute(
        serverDir: Path,
        pluginsDir: Path,
        tomlFile: Path,
        toml: JarletToml,
        identifier: String,
        sourceOverride: String?,
        policy: JarletToml.Policy,
        trustRequested: Boolean,
    ): Declaration {
        val (source, id) = if (sourceOverride != null) {
            SourceResolver.validateSourceIdShape(sourceOverride, identifier)
            sourceOverride to identifier
        } else {
            val resolved = SourceResolver.resolveAddIdentifier(identifier)
            Log.info("""Resolved "$identifier" to ${resolved.id} (${resolved.source})""")
            resolved.source to resolved.id
        }

        SourceResolver.checkIdAvailable(toml, id)

        val updatedToml = toml.copy(plugins = toml.plugins + JarletToml.Plugin(source = source, id = id, policy = policy))

        Log.info("Note: this rewrites $tomlFile in full; hand-written comments and formatting are not preserved.")
        updatedToml.write(tomlFile)

        PluginRouter.route(serverDir, pluginsDir, source, id, policy, trustRequested)

        return Declaration(updatedToml, source, id)
    }
}
