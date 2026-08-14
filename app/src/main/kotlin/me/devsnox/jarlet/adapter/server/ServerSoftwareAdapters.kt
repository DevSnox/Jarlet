package me.devsnox.jarlet.adapter.server

/** Thrown when `[server.package].name` names a source with no registered adapter. */
class ServerSoftwareAdapterException(message: String) : Exception(message)

/**
 * Static registry resolving `[server.package].name` to its adapter.
 *
 * Paper is the only server-software source that exists today, so this
 * stays a plain `Map` built from a one-element list rather than a more
 * elaborate registration mechanism -- there is nothing yet to justify
 * more structure than that. Add further adapters to the `listOf(...)`
 * below as new sources are implemented (e.g. a hypothetical
 * `PurpurAdapter`).
 */
object ServerSoftwareAdapters {
    private val adapters: Map<String, ServerSoftwareAdapter> =
        listOf(PaperAdapter).associateBy { it.id }

    /** Resolves the adapter for `[server.package].name` value [id], throwing if unknown. */
    fun find(id: String): ServerSoftwareAdapter =
        adapters[id]
            ?: throw ServerSoftwareAdapterException(
                "Unknown [server.package].name '$id'; no adapter is implemented for this package",
            )
}
