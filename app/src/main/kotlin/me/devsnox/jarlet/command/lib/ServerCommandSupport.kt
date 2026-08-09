package me.devsnox.jarlet.command.lib

import com.github.ajalt.clikt.core.CliktError

/** Thrown for the same failure cases `fail()` covers in the ported bash scripts (`src/lib/lib.sh`, `src/server/stop.sh`). */
class ServerCommandException(message: String) : Exception(message)

/**
 * Wraps a server-lifecycle command's body, translating [ServerCommandException]
 * -- and any other unexpected exception (config errors, adapter failures,
 * I/O) -- into a [CliktError] so Clikt prints a single `Error: <message>`
 * line to stderr and exits non-zero. This is the Kotlin equivalent of
 * `fail()` in `src/lib/lib.sh` (and its inline copy in `src/server/stop.sh`),
 * applied once at the command boundary rather than call-by-call.
 */
internal fun serverCommandBody(block: () -> Unit) {
    try {
        block()
    } catch (e: CliktError) {
        throw e
    } catch (e: Exception) {
        throw CliktError("Error: ${e.message}")
    }
}
