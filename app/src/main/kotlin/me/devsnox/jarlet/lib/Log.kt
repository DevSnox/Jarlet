package me.devsnox.jarlet

import java.io.PrintStream

/**
 * Hand-rolled process-global logger: no SLF4J/Logback/kotlin-logging,
 * since a CLI that needs a handful of print functions and a debug flag
 * doesn't warrant the GraalVM native-image reflection friction those
 * bring.
 *
 * Plain top-level `object`, no `CliktCommand` dependency -- reachable from
 * anywhere, including non-command singletons like `SharedHttp` and the
 * adapters, which have no `Context` to pull an option from.
 *
 * [debugEnabled] is process-global mutable state, harmless for a
 * single-invocation CLI (never runs two "sessions" in one JVM) but worth
 * flagging so a future contributor doesn't reach for it inside, say, a
 * long-lived daemon mode without reconsidering.
 *
 * [out]/[err] are swappable sinks, defaulting to the real
 * `System.out`/`System.err`. They exist purely so tests can redirect this
 * object's output: Clikt's own `CliktCommand.test()` extension captures
 * output *only* when it is written through the Mordant `Terminal`/
 * `TerminalRecorder` it installs into the command's `Context` for the
 * duration of the call (verified against clikt-jvm 5.1.0's and
 * mordant-jvm 3.0.2's sources) -- plain `println`/`System.err.println`
 * calls are invisible to it. Since `Log` intentionally has no `Context` to
 * pull a `Terminal` from, it cannot route through that mechanism, so
 * `CommandTestSupport` instead points [out]/[err] at buffers it can read
 * back out and merge into the test result. Production behavior is
 * unchanged: nobody reassigns these outside tests, so real runs keep
 * writing to the real streams.
 */
object Log {
    var debugEnabled: Boolean = false
        private set

    var out: PrintStream = System.out
    var err: PrintStream = System.err

    fun enableDebug() {
        debugEnabled = true
    }

    /** Gated by `--debug` (see [Jarlet]); off by default. Written to stderr, same stream [warn]/[error] use for diagnostic-shaped output. */
    fun debug(message: String) {
        if (debugEnabled) err.println("[debug] $message")
    }

    /** Always-shown, normal successful-path output (declaration confirmations, install summaries, etc). Written to stdout. */
    fun info(message: String) = out.println(message)

    fun warn(message: String) = err.println("Warning: $message")
    fun error(message: String) = err.println("Error: $message")
}
