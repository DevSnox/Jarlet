package me.devsnox.jarlet.plugin

import me.devsnox.jarlet.Log
import me.devsnox.jarlet.http.SharedHttp
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The core trust-fallback gate for an externally-hosted plugin download --
 * Kotlin port of `src/plugin/trust.sh`'s `handle_untrusted_external_url()`,
 * called by an adapter's external-hosting check after
 * [ExternalUrlRedirector.tryResolve] has already failed to recognize the
 * URL as belonging to a known adapter. Handles all three outcomes itself
 * (already trusted -> download; `trustRequested` -> trust once, then
 * download; neither -> print the skip message) so [HangarAdapter]/
 * [SpigetAdapter] each need only one call site instead of duplicating this
 * branching.
 *
 * Never throws for "not trusted, not requested" (mirrors the bash
 * function's "skip is not a failure" convention, matching every adapter's
 * `return 0` on a skip) -- only throws once a download has actually been
 * allowed to start and then fails outright (network failure, verified
 * checksum mismatch, empty file).
 */
object UntrustedExternalDownloader {
    /** Thrown only once a trusted-domain download has actually started and then fails. */
    class UntrustedExternalDownloadException(message: String) : Exception(message)

    /**
     * @param source the declaring adapter's own source (e.g. `"hangar"`), used only for the skip/success messages and to namespace the `plugins-state.json` entry
     * @param id the declaring adapter's own id (e.g. `"Geyser"`)
     * @param label human-readable name to print (falls back to [id] when a source has no separate display name)
     * @param externalUrl the off-adapter URL to fetch from if trust allows it
     * @param expectedHash optional -- almost always `null` in practice (Hangar's own `fileInfo` is confirmed `null` whenever `externalUrl` is set), but honored in case a future project (or another adapter) ever does report both
     * @param expectedSize optional, same rationale as [expectedHash]
     * @param fallbackFilename used when the download has no usable `Content-Disposition` filename
     * @param trustRequested threaded down from the CLI's `--trust` flag
     * @param versionName OPTIONAL real version identity, when the caller already resolved one before reaching the external-hosting branch (Hangar's case -- see the param doc on `handle_untrusted_external_url()` in `trust.sh` for the full reasoning). Defaults to the literal `"external"` (deliberately not null/empty -- see below) for callers with no version context (Spiget's case).
     * @param channelName OPTIONAL, same rationale as [versionName]
     */
    fun handle(
        serverDir: Path,
        pluginsDir: Path,
        source: String,
        id: String,
        label: String,
        externalUrl: String,
        expectedHash: String?,
        expectedSize: Long?,
        fallbackFilename: String,
        trustRequested: Boolean,
        versionName: String = "external",
        channelName: String? = null,
    ) {
        val domain = TrustedSourceStore.domainOf(externalUrl)
        Log.debug("resolved domain '$domain' for $externalUrl, trusted=${TrustedSourceStore.isTrusted(domain)}")

        when {
            TrustedSourceStore.isTrusted(domain) -> {
                Log.info("\"$label\" is hosted externally at $externalUrl -- domain \"$domain\" is trusted, downloading directly")
            }
            trustRequested -> {
                TrustedSourceStore.trust(domain)
                Log.info(
                    "\"$label\" is hosted externally at $externalUrl -- trusting domain \"$domain\" (saved to ${TrustedSourceStore.file()}) and downloading directly",
                )
            }
            else -> {
                Log.info("Skipping \"$label\": hosted externally, install manually: $externalUrl")
                Log.info(
                    "Or re-run this command with --trust to trust the \"$domain\" domain and download it directly (best-effort verification only -- see ${TrustedSourceStore.file()})",
                )
                return
            }
        }

        val temporary = Files.createTempFile(pluginsDir, ".trust-download-", ".tmp")
        try {
            Log.info("Downloading $label from $externalUrl")

            val download = try {
                SharedHttp.download(externalUrl, temporary)
            } catch (e: IOException) {
                throw UntrustedExternalDownloadException("Download failed for '$label' from $externalUrl")
            }

            if (download.size <= 0) {
                throw UntrustedExternalDownloadException("'$label' downloaded as an empty file from $externalUrl")
            }

            var verifiedHash: String? = null

            when {
                !expectedHash.isNullOrEmpty() -> {
                    // A real checksum was available despite the external hosting (see the param doc above) -- verify it for real, same as any adapter-hosted download.
                    val actualHash = SharedHttp.sha256Hex(temporary)
                    if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                        throw UntrustedExternalDownloadException(
                            "'$label' SHA-256 verification failed for external download from $externalUrl",
                        )
                    }
                    verifiedHash = expectedHash
                    Log.info("SHA-256 verified (checksum was available from $source despite external hosting): $verifiedHash")
                }
                expectedSize != null && expectedSize > 0 -> {
                    if (download.size != expectedSize) {
                        // Stays on stdout (Log.info, not Log.warn) -- this
                        // was a plain println() before this migration, not
                        // one routed to stderr, so Log.warn()'s stderr
                        // stream would be a real behavior change here.
                        // "Warning: " is kept as literal text (not
                        // Log.warn()'s own prefix) for the same reason.
                        Log.info(
                            "Warning: \"$label\" downloaded size (${download.size} bytes) does not match the expected size ($expectedSize bytes) -- no cryptographic checksum was available to verify further, proceeding anyway since this domain is trusted",
                        )
                    }
                }
                else -> {
                    // See the comment above -- same stdout-preserving rationale.
                    Log.info(
                        "Warning: no checksum or size is available to verify this trusted external download -- \"$label\" was fetched as-is from $externalUrl with no cryptographic verification",
                    )
                }
            }

            val fileName = download.fileName ?: fallbackFilename
            val target = pluginsDir.resolve(fileName)
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)

            // version_name: real version identity when the caller has one to
            // give, otherwise the fixed literal "external" (not null/empty),
            // which is deliberate for callers with no version context at
            // all: it guarantees a future PluginStateStore.read() comparison
            // never coincidentally matches some future value, so a caller
            // with no real identity to compare against always re-downloads
            // on every explicit invocation rather than silently
            // short-circuiting on an identity this store has no way to
            // trust. A fixed non-null literal also keeps a future list
            // command's display showing a real (if generic) value instead of
            // misreporting an actually-installed trusted jar as "not
            // installed".
            //
            // When the caller left `versionName` at that generic default
            // (Spiget's case), OR passed a value that is really just the
            // adapter's own `id` echoed back rather than a genuine version
            // (Hangar's live Geyser data: `/latest?channel=...` resolves to
            // the literal label "Geyser", i.e. Hangar's channel-latest label
            // for this project equals its own project id -- a known Hangar
            // data quirk, not a real version identity), best-effort-check
            // the jar's own bundled plugin.yml for a real version before
            // falling back to whatever the caller passed. Never overrides a
            // versionName that differs from both the generic literal and
            // the id -- that's assumed to be a genuinely-resolved value
            // (e.g. Hangar's `targetVersion` for any other project).
            val versionNameIsGeneric = versionName == "external" || versionName.equals(id, ignoreCase = true)
            val resolvedVersionName = if (versionNameIsGeneric) {
                PluginYamlReader.read(target)?.version ?: versionName
            } else {
                versionName
            }

            PluginStateStore.write(
                serverDir,
                InstalledVersion(
                    source = source,
                    id = id,
                    versionName = resolvedVersionName,
                    versionId = null,
                    channelName = channelName,
                    sha256 = verifiedHash,
                    size = download.size,
                    file = fileName,
                    external = true,
                ),
            )

            Log.info("Installed $label as $target (trusted external download, external=true)")
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
