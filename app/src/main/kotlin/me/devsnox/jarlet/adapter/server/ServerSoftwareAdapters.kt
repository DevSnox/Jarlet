package me.devsnox.jarlet.adapter.server

/** Thrown when `[server].package` names a source with no registered adapter. */
class ServerSoftwareAdapterException(message: String) : Exception(message)

/**
 * Static registry of server-software adapters -- the migration plan's
 * architecture decision #2 (static, reflection-free registry) applied to
 * `[server].package` resolution. Kotlin equivalent of `install.sh`'s
 * `dispatch_install()`, minus the dynamic file-sourcing: adapters are
 * listed here by hand instead of being discovered by convention
 * (`../adapter/server/$package.sh`).
 *
 * Paper is the only server-software source that exists today, so this
 * stays a plain `Map` built from a one-element list rather than a more
 * elaborate registration mechanism -- there is nothing yet to justify
 * more structure than that. Add further adapters to the `listOf(...)`
 * below as they're ported (e.g. a hypothetical `PurpurAdapter`).
 */
object ServerSoftwareAdapters {
    private val adapters: Map<String, ServerSoftwareAdapter> =
        listOf(PaperAdapter).associateBy { it.id }

    /** Resolves the adapter for `[server].package` value [id], failing the way `dispatch_install()` does if unknown. */
    fun find(id: String): ServerSoftwareAdapter =
        adapters[id]
            ?: throw ServerSoftwareAdapterException(
                "Unknown [server].package '$id'; no adapter is implemented for this package",
            )
}
