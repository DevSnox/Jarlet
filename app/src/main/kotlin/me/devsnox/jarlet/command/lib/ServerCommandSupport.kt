package me.devsnox.jarlet.command.lib

import com.github.ajalt.clikt.core.CliktError

/**
 * Wraps a server-lifecycle command's body, translating
 * [me.devsnox.jarlet.service.JarletServiceException] -- and any other
 * unexpected exception (config errors, adapter failures, I/O) -- into a
 * [CliktError] so Clikt prints a single `Error: <message>` line to stderr
 * and exits non-zero, applied once at the command boundary rather than
 * call-by-call.
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
