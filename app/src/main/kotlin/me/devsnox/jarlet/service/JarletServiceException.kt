package me.devsnox.jarlet.service

/**
 * Typed failure surface for the service layer -- thrown by [ServerService]/
 * [PluginService] (and the domain code they delegate to, e.g.
 * [me.devsnox.jarlet.server.ServerSetup], [me.devsnox.jarlet.server.ServerPaths])
 * instead of a CLI-specific exception, so a non-CLI frontend (a planned MCP
 * server, a possible REST API) can map each subtype to its own error shape
 * -- an HTTP status code, an MCP error code -- by matching on type instead
 * of sniffing [message] text. The CLI itself still just prints [message]
 * and exits non-zero for any of them, generically, via
 * [me.devsnox.jarlet.command.lib.serverCommandBody].
 */
sealed class JarletServiceException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** The named server, plugin, or file does not exist. A REST caller would map this to 404. */
    class NotFound(message: String) : JarletServiceException(message)

    /** The request is well-formed but conflicts with current state (already exists, already/not running, wrong process at a recorded PID). A REST caller would map this to 409. */
    class Conflict(message: String) : JarletServiceException(message)

    /** The request itself is malformed: a bad flag combination, an invalid format, an out-of-range value. A REST caller would map this to 400. */
    class InvalidInput(message: String) : JarletServiceException(message)

    /** A third-party plugin/server-software source (Hangar, Spiget, GitHub, Paper) failed or refused the request. A REST caller would map this to 502/503. */
    class ExternalSourceError(message: String, cause: Throwable? = null) : JarletServiceException(message, cause)

    /** The operation was attempted but did not complete as expected: an install failure, a misconfigured `jarlet-sys.conf`, a startup/shutdown timeout. A REST caller would map this to 500. */
    class OperationFailed(message: String, cause: Throwable? = null) : JarletServiceException(message, cause)
}
