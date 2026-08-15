package me.devsnox.jarlet.adapter.server

/** Thrown when `[server.package].name` names a source with no registered adapter. */
class ServerSoftwareAdapterException(message: String) : Exception(message)

/**
 * Static registry resolving `[server.package].name` to its adapter.
 *
 * Paper and Velocity are both served by PaperMC's downloads repository, so
 * they intentionally share one adapter. The registry expands the adapter's
 * supported package names into direct lookup entries.
 */
object ServerSoftwareAdapters {
    private val adapters: Map<String, ServerSoftwareAdapter> =
        listOf(PaperMcAdapter).flatMap { adapter ->
            adapter.supportedPackages.map { it to adapter }
        }.toMap()

    /** Resolves the adapter for `[server.package].name` value [id], throwing if unknown. */
    fun find(id: String): ServerSoftwareAdapter =
        adapters[id]
            ?: throw ServerSoftwareAdapterException(
                "Unknown [server.package].name '$id'; no adapter is implemented for this package",
            )
}
