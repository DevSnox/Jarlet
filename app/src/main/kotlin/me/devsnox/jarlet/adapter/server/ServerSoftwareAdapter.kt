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
     * Returns the Minecraft version actually installed.
     *
     * Implementations are expected to throw on any failure (unsupported
     * version, network failure, checksum mismatch, ...) rather than
     * returning a status -- callers should let that propagate up to the
     * command layer, which is responsible for turning it into a
     * user-facing error.
     *
     * [policy] is the `[server].policy` version-selection policy. Under
     * `track = "minor"`/`"patch"`, [minecraftVersion] is a movable baseline
     * rather than a fixed target -- an implementation MAY resolve and
     * install a higher version within that bound (see [PaperAdapter]) and
     * report the resolved version back through the return value, which is
     * exactly `minecraftVersion` unchanged for every other policy shape
     * (pin, track=latest/channel, no policy). Callers use the returned
     * version -- not the [minecraftVersion] argument -- when recording what
     * was actually installed (see `ServerStateStore`), so drift-detection
     * on the next reconciliation compares against reality rather than a
     * baseline that may have already been advanced past.
     */
    fun install(minecraftVersion: String, target: Path, policy: JarletToml.Policy = JarletToml.Policy()): String
}
