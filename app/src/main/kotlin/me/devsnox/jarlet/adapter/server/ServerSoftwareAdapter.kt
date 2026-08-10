package me.devsnox.jarlet.adapter.server

import me.devsnox.jarlet.config.JarletToml
import java.nio.file.Path

/**
 * Contract for a server-software source adapter -- Kotlin equivalent of the
 * self-describing, dynamically-dispatched contract documented at the top of
 * `src/adapter/server/paper.sh` (`ADAPTER_SOURCE_NAME`/
 * `ADAPTER_ENTRY_FUNCTION`, dispatched by `install.sh`'s
 * `dispatch_install()`).
 *
 * Per the migration plan's architecture decision #2, the dynamic-dispatch
 * mechanics (sourcing a shell file, reading back dynamically-set
 * variables, indirect expansion) are dropped entirely -- each source is
 * instead a Kotlin `object` implementing this interface directly, and
 * resolved through a small static map (see [ServerSoftwareAdapters]).
 * That keeps the spirit of the bash contract (one self-contained unit per
 * source, sanity-checked by name) without anything reflection-based, which
 * matters for a GraalVM native-image build.
 */
interface ServerSoftwareAdapter {
    /** The `[server].package` value this adapter handles, e.g. `"paper"`. */
    val id: String

    /**
     * Downloads and verifies a server jar for [minecraftVersion], writing
     * the result to [target]. Mirrors the bash contract's
     * `install_<package>_server(minecraft_version, target)` entry point.
     *
     * Implementations are expected to throw on any failure (unsupported
     * version, network failure, checksum mismatch, ...) rather than
     * returning a status -- callers should let that propagate up to the
     * command layer, which is responsible for turning it into a
     * user-facing error.
     *
     * [policy] is the `[server].policy` version-selection policy
     * (pin/track+channel), threaded through ahead of a planned 2nd server
     * adapter that will need it -- it is currently unused by any adapter,
     * since Paper doesn't yet support pinning/channel-tracking a server
     * build.
     */
    fun install(minecraftVersion: String, target: Path, policy: JarletToml.Policy = JarletToml.Policy())
}
