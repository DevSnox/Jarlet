package me.devsnox.jarlet.plugin

import me.devsnox.jarlet.config.JarletVersion
import me.devsnox.jarlet.config.SysConfig
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Small shared HTTP/hashing plumbing used by all three plugin source
 * adapters ([me.devsnox.jarlet.adapter.plugin.HangarAdapter],
 * [me.devsnox.jarlet.adapter.plugin.GithubReleasesAdapter],
 * [me.devsnox.jarlet.adapter.plugin.SpigetAdapter]) plus [SourceResolver]
 * and [UntrustedExternalDownloader] -- every one of those bash counterparts
 * (`hangar.sh`/`github-releases.sh`/`spiget.sh`/`resolve.sh`/`trust.sh`)
 * repeats the same handful of `curl` invocation shapes (a plain GET
 * capturing status+body via `--write-out '\n%{http_code}'`, a status-only
 * existence probe via `--output /dev/null --write-out '%{http_code}'`, and
 * a retried file download via `--retry 3` with `--dump-header` for
 * `Content-Disposition`), so factoring them once here avoids repeating that
 * boilerplate five times over. This is plumbing shared across the plugin
 * subsystem, not a competing adapter contract -- [PluginSourceAdapter]
 * remains the only interface adapters implement.
 *
 * Follows [me.devsnox.jarlet.adapter.server.PaperAdapter]'s precedent:
 * `java.net.http.HttpClient` for HTTP, no extra dependency, per the
 * migration plan's recommended defaults.
 */
object PluginHttp {
    private val httpClient: HttpClient by lazy {
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    /** `PROJECT_NAME/<jarlet-version> (REPO_URL)` -- same construction as `$USER_AGENT` throughout the bash prototype. */
    val userAgent: String by lazy {
        val sysConfig = SysConfig.default()
        "${sysConfig.value("PROJECT_NAME")}/${JarletVersion.VERSION} (${sysConfig.value("REPO_URL")})"
    }

    data class Response(val status: Int, val body: String)

    /**
     * Plain GET against [url] with [headers] (plus `User-Agent`), returning
     * the HTTP status and body together -- mirrors every adapter's
     * `curl ... --write-out '\n%{http_code}'` pattern. Throws [IOException]
     * on a network-level failure (mirrors curl's own non-zero exit before
     * any status is even produced); a non-2xx HTTP response is NOT an
     * exception here -- callers decide what a given status means (a 404 is
     * "skip" for github-releases, but a hard failure for hangar/spiget).
     */
    fun get(url: String, headers: Map<String, String> = emptyMap()): Response {
        val request = requestBuilder(url, headers).GET().build()
        val response = send(request, HttpResponse.BodyHandlers.ofString())
        return Response(response.statusCode(), response.body())
    }

    /**
     * Existence-probe GET: discards the body, returns only the HTTP status
     * code, or `0` on any network-level failure -- mirrors resolve.sh's
     * `resolve_http_status()` (which falls back to the literal string
     * `"000"` on a curl failure; `0` plays the same "definitely not 2xx"
     * role here).
     */
    fun statusOnly(url: String, headers: Map<String, String> = emptyMap()): Int =
        try {
            get(url, headers).status
        } catch (e: IOException) {
            0
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            0
        }

    /** Form-urlencoded POST against [url] -- used only by Hangar's `/authenticate`. */
    fun post(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap()): Response {
        val encoded = form.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, StandardCharsets.UTF_8)}=${URLEncoder.encode(v, StandardCharsets.UTF_8)}"
        }
        val request = requestBuilder(url, headers)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(encoded))
            .build()
        val response = send(request, HttpResponse.BodyHandlers.ofString())
        return Response(response.statusCode(), response.body())
    }

    data class Download(val size: Long, val fileName: String?)

    /**
     * Downloads [url] to [target] (overwriting it), retrying up to
     * [retries] times on any failure -- mirrors `curl --retry 3` (the only
     * retried step in any adapter; metadata GETs are not retried, same as
     * the bash prototype). Returns the final file size and any
     * `Content-Disposition` filename reported (mirrors `--dump-header` +
     * the `grep`/`sed` filename extraction every download call site
     * repeats). Throws once all attempts are exhausted, or immediately on a
     * non-2xx final status.
     */
    fun download(
        url: String,
        target: Path,
        headers: Map<String, String> = emptyMap(),
        retries: Int = 3,
    ): Download {
        var lastError: Exception? = null

        repeat(retries) {
            try {
                val request = requestBuilder(url, headers).GET().build()
                val response = send(request, HttpResponse.BodyHandlers.ofFile(target))
                if (response.statusCode() !in 200..299) {
                    throw IOException("HTTP ${response.statusCode()}")
                }
                val fileName = contentDispositionFileName(
                    response.headers().firstValue("Content-Disposition").orElse(null),
                )
                return Download(Files.size(target), fileName)
            } catch (e: Exception) {
                lastError = e
            }
        }

        throw IOException("Download failed" + (lastError?.message?.let { ": $it" } ?: ""), lastError)
    }

    /** SHA-256 of [path]'s contents, as lowercase hex -- mirrors every adapter's `shasum -a 256` call. */
    fun sha256Hex(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun requestBuilder(url: String, headers: Map<String, String>): HttpRequest.Builder {
        var builder = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", userAgent)
        for ((key, value) in headers) {
            builder = builder.header(key, value)
        }
        return builder
    }

    private fun <T> send(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> =
        try {
            httpClient.send(request, handler)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Request interrupted", e)
        }

    /**
     * Extracts a `filename` from a `Content-Disposition` header value, or
     * `null` if absent/unparseable. Prefers the RFC 5987 `filename*=`
     * extended parameter (`charset'lang'percent-encoded-value`) over the
     * plain `filename=` one, per RFC 6266, decoding whichever is found --
     * a raw, undecoded value is not safe to write to disk as-is. Also
     * decodes an RFC 2047 MIME encoded-word (`=?charset?Q|B?...?=`) inside
     * a plain `filename=`, since at least one real adapter (GeyserMC's
     * download endpoint) wraps a plain-ASCII filename in one even though
     * it's neither required nor valid per the HTTP spec -- observed live as
     * `filename="=?UTF-8?Q?Geyser-Spigot.jar?="`, which without decoding
     * became the literal on-disk filename.
     */
    private fun contentDispositionFileName(header: String?): String? {
        if (header.isNullOrEmpty()) return null

        val extended = Regex("""filename\*=([^"';]+)""", RegexOption.IGNORE_CASE).find(header)
        if (extended != null) {
            return decodeRfc5987(extended.groupValues[1].trim())
        }

        val plain = Regex("""filename=["']?([^"';]+)["']?""", RegexOption.IGNORE_CASE).find(header)
        return plain?.groupValues?.get(1)?.trim()?.let { decodeRfc2047(it) }
    }

    /** Decodes an RFC 5987 `charset'lang'percent-encoded-value` extended parameter value. */
    private fun decodeRfc5987(value: String): String? {
        val parts = value.split("'", limit = 3)
        if (parts.size != 3) return value
        val (charset, _, encoded) = parts
        return try {
            java.net.URLDecoder.decode(encoded, charset.ifBlank { "UTF-8" })
        } catch (e: Exception) {
            encoded
        }
    }

    /** Decodes an RFC 2047 MIME encoded-word (`=?charset?Q?...?=` or `=?charset?B?...?=`); returns [value] unchanged if it isn't one. */
    private fun decodeRfc2047(value: String): String {
        val match = Regex("""^=\?([^?]+)\?([QqBb])\?([^?]*)\?=$""").find(value) ?: return value
        val (charset, encoding, encoded) = match.destructured
        return try {
            when (encoding.uppercase()) {
                "B" -> String(java.util.Base64.getDecoder().decode(encoded), charset(charset))
                "Q" -> {
                    val bytes = java.io.ByteArrayOutputStream()
                    var i = 0
                    while (i < encoded.length) {
                        val c = encoded[i]
                        when (c) {
                            '_' -> { bytes.write(' '.code); i++ }
                            '=' -> {
                                bytes.write(encoded.substring(i + 1, i + 3).toInt(16))
                                i += 3
                            }
                            else -> { bytes.write(c.code); i++ }
                        }
                    }
                    String(bytes.toByteArray(), charset(charset))
                }
                else -> value
            }
        } catch (e: Exception) {
            value
        }
    }
}
