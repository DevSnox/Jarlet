package me.devsnox.jarlet

/**
 * Hand-rolled process-global logger -- see
 * `prototyping/documentation/concepts/logging.md` for the full rationale
 * (no SLF4J/Logback/kotlin-logging: GraalVM native-image reflection
 * friction for a CLI that needs four print functions and a boolean).
 *
 * Plain top-level `object`, no `CliktCommand` dependency -- reachable from
 * anywhere, including non-command singletons like `PluginHttp` and the
 * adapters, which have no `Context` to pull an option from.
 *
 * [debugEnabled] is process-global mutable state, harmless for a
 * single-invocation CLI (never runs two "sessions" in one JVM) but worth
 * flagging so a future contributor doesn't reach for it inside, say, a
 * long-lived daemon mode without reconsidering.
 */
object Log {
    var debugEnabled: Boolean = false
        private set

    fun enableDebug() {
        debugEnabled = true
    }

    /** Gated by `--debug` (see [Jarlet]); off by default. Written to stderr, same stream [warn]/[error] use for diagnostic-shaped output. */
    fun debug(message: String) {
        if (debugEnabled) System.err.println("[debug] $message")
    }

    /** Always-shown, normal successful-path output (declaration confirmations, install summaries, etc). Written to stdout -- the stream this kind of user-facing output already used before this migration. */
    fun info(message: String) = println(message)

    fun warn(message: String) = System.err.println("Warning: $message")
    fun error(message: String) = System.err.println("Error: $message")
}
