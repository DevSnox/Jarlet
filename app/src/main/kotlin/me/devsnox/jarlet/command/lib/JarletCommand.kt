package me.devsnox.jarlet.command.lib

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.eagerOption
import me.devsnox.jarlet.Log

/**
 * Shared base for every `jarlet` subcommand ([me.devsnox.jarlet.command.SetupCommand],
 * [me.devsnox.jarlet.command.StartCommand], etc, plus the `plugin` group and its own
 * children) so each one accepts `--debug` in the position users actually
 * type it in.
 *
 * Background: [me.devsnox.jarlet.Jarlet] (the root command) declares
 * `--debug` itself and reads it in its own `run()`, which fires before any
 * subcommand body -- but Clikt scopes an option to whichever command's
 * token span it appears in on the command line. Tokens after a subcommand
 * name belong to that subcommand's own parser, not the root's, so
 * `jarlet --debug setup test3` worked while `jarlet setup test3 --debug`
 * (the order most users reach for first) failed with "no such option
 * --debug". There's no Clikt context flag (`allowInterspersedArgs` et al
 * -- checked against clikt-jvm 5.1.0's sources) that makes a
 * parent-declared option resolve after a subcommand token; the flag has to
 * be registered on whichever command instance actually owns those trailing
 * tokens.
 *
 * Registered via [eagerOption] rather than a plain `option().flag()`
 * property: the callback fires the moment the flag is parsed, independent
 * of whatever a subclass's own `run()` does (including the ones that route
 * their body through [serverCommandBody] instead of calling anything
 * directly), so every subcommand gets the same "enable debug before the
 * body executes" behavior as the root without each one needing its own
 * `if (debug) Log.enableDebug()` line. The effect is identical to the
 * root's: flips the single process-global [Log.debugEnabled] bit.
 *
 * The root [me.devsnox.jarlet.Jarlet] command deliberately keeps its own
 * separate `--debug` declaration (still needed for `jarlet --debug
 * <subcommand>` and bare `jarlet --debug`) rather than extending this --
 * it isn't a subcommand and has its own doc comment justifying the
 * eager-read-in-run() shape.
 */
abstract class JarletCommand(name: String) : CliktCommand(name = name) {
    init {
        eagerOption("--debug", help = "Print verbose diagnostic detail (HTTP calls, retries, timing).") {
            Log.enableDebug()
        }
    }
}
