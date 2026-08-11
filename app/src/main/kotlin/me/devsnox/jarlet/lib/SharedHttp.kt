package me.devsnox.jarlet.lib

import me.devsnox.jarlet.Log
import me.devsnox.jarlet.config.JarletVersion
import me.devsnox.jarlet.config.SysConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import kotlin.collections.iterator

/**
 * Small shared HTTP/hashing plumbing used across every subsystem that talks
 * to an external HTTP API or downloads/verifies a file: all three plugin
 * source adapters ([me.devsnox.jarlet.adapter.plugin.HangarAdapter],
 * [me.devsnox.jarlet.adapter.plugin.GithubAdapter],
 * [me.devsnox.jarlet.adapter.plugin.SpigetAdapter]), the server-software
 * adapter ([me.devsnox.jarlet.adapter.server.PaperAdapter]), plus
 * [me.devsnox.jarlet.plugin.SourceResolver] and
 * [me.devsnox.jarlet.plugin.UntrustedExternalDownloader]. Deliberately
 * subsystem-neutral (package `me.devsnox.jarlet.lib`, not
 * `me.devsnox.jarlet.plugin`) since it serves both the plugin and
 * server-software adapters, not just plugin plumbing. This is plumbing
 * shared across subsystems, not a competing adapter contract --
 * [me.devsnox.jarlet.plugin.PluginSourceAdapter] and
 * [me.devsnox.jarlet.adapter.server.ServerSoftwareAdapter] remain the only
 * interfaces adapters implement.
 *
 * Uses `java.net.http.HttpClient` for HTTP, no extra dependency.
 */
object SharedHttp {
    private val httpClient: HttpClient by lazy {
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    /** `PROJECT_NAME/<jarlet-version> (REPO_URL)`, used as the `User-Agent` header on every request. */
    val userAgent: String by lazy {
        val sysConfig = SysConfig.default()
        "${sysConfig.value("PROJECT_NAME")}/${JarletVersion.VERSION} (${sysConfig.value("REPO_URL")})"
    }

    /**
     * [headers] is response headers keyed by lower-cased header name (HTTP
     * header names are case-insensitive, and `java.net.http`'s
     * `HttpHeaders` already preserves multiple values per name via
     * `firstValue`/`allValues`; only the first value per name is kept here
     * since every current/anticipated caller -- e.g. `x-ratelimit-remaining`,
     * `x-ratelimit-reset` -- only ever needs a single value). Empty for
     * [Download]'s call path (not populated there; only [get] and [post]
     * populate it) and for any pre-existing construction site that doesn't
     * pass one, so this is purely additive.
     */
    data class Response(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())

    /**
     * Plain GET against [url] with [headers] (plus `User-Agent`), returning
     * the HTTP status and body together. Throws [IOException] on a
     * network-level failure; a non-2xx HTTP response is NOT an exception
     * here -- callers decide what a given status means (a 404 is "skip"
     * for github, but a hard failure for hangar/spiget).
     */
    fun get(url: String, headers: Map<String, String> = emptyMap()): Response {
        val request = requestBuilder(url, headers).GET().build()
        val start = System.currentTimeMillis()
        val response = send(request, HttpResponse.BodyHandlers.ofString())
        Log.debug("GET $url -> ${response.statusCode()} (${System.currentTimeMillis() - start}ms)")
        return Response(response.statusCode(), response.body(), responseHeaders(response))
    }

    /**
     * Existence-probe GET: discards the body, returns only the HTTP status
     * code, or `0` on any network-level failure (a value that can never be
     * a real 2xx status).
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
        val start = System.currentTimeMillis()
        val response = send(request, HttpResponse.BodyHandlers.ofString())
        Log.debug("POST $url -> ${response.statusCode()} (${System.currentTimeMillis() - start}ms)")
        return Response(response.statusCode(), response.body(), responseHeaders(response))
    }

    data class Download(val size: Long, val fileName: String?)

    /**
     * Downloads [url] to [target] (overwriting it), retrying up to
     * [retries] times on any failure -- the only retried step in any
     * adapter; metadata GETs are not retried. Returns the final file size
     * and any `Content-Disposition` filename reported. Throws once all
     * attempts are exhausted, or immediately on a non-2xx final status.
     */
    fun download(
        url: String,
        target: Path,
        headers: Map<String, String> = emptyMap(),
        retries: Int = 3,
    ): Download {
        var lastError: Exception? = null

        repeat(retries) { attempt ->
            try {
                val request = requestBuilder(url, headers).GET().build()
                val response = send(request, HttpResponse.BodyHandlers.ofFile(target))
                if (response.statusCode() !in 200..299) {
                    throw IOException("HTTP ${response.statusCode()}")
                }

                // BodyHandlers.ofFile() does not itself guarantee the file on
                // disk actually holds every byte the server advertised -- a
                // connection that closes early on a non-chunked HTTP/1.1
                // response can leave `send()` returning normally (no
                // exception) with a truncated file quietly written to
                // [target] (observed live: Geyser's CDN response, once
                // truncated this way, decodes its Content-Disposition
                // filename correctly but the bytes on disk are a partial,
                // invalid ZIP/jar). curl detects exactly this case itself
                // (exit code 18, "transfer closed with outstanding read data
                // remaining"), so mirror that here using the one signal
                // available up front: a declared `Content-Length` that
                // doesn't match what actually landed on disk means the
                // transfer was cut short -- treat it as a failed attempt so
                // the retry loop below gets a chance at a clean transfer
                // instead of silently handing back partial bytes as if they
                // were a complete download.
                val actualSize = Files.size(target)
                val declaredSize = response.headers().firstValue("Content-Length")
                    .orElse(null)?.toLongOrNull()
                if (declaredSize != null && actualSize != declaredSize) {
                    throw IOException("Truncated download: got $actualSize bytes, expected $declaredSize")
                }

                val fileName = contentDispositionFileName(
                    response.headers().firstValue("Content-Disposition").orElse(null),
                )
                return Download(actualSize, fileName)
            } catch (e: Exception) {
                Log.debug("download attempt ${attempt + 1}/$retries failed for $url: ${e.message}")
                lastError = e
            }
        }

        throw IOException("Download failed" + (lastError?.message?.let { ": $it" } ?: ""), lastError)
    }

    /** SHA-256 of [path]'s contents, as lowercase hex. */
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
        return HexFormat.of().formatHex(digest.digest())
    }

    /** Flattens `java.net.http`'s multi-value [HttpResponse.headers] into a single-value, lower-cased-key map (see [Response.headers] doc for why first-value-only is sufficient here). */
    private fun responseHeaders(response: HttpResponse<*>): Map<String, String> =
        response.headers().map().entries.associate { (name, values) -> name.lowercase() to values.first() }

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
    internal fun contentDispositionFileName(header: String?): String? {
        if (header.isNullOrEmpty()) return null

        // RFC 5987's filename*= value (charset'lang'value) is DELIMITED by
        // single quotes, so the capture group must not exclude '\'' --
        // excluding it (as an earlier version of this regex did) truncates
        // "UTF-8''Geyser-Spigot.jar" down to just "UTF-8" at the first
        // quote, which then fails decodeRfc5987()'s 3-part split and comes
        // back out as the literal string "UTF-8" instead of a filename.
        val extended = Regex("""filename\*=([^";]+)""", RegexOption.IGNORE_CASE).find(header)
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
            URLDecoder.decode(encoded, charset.ifBlank { "UTF-8" })
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
                "B" -> String(Base64.getDecoder().decode(encoded), charset(charset))
                "Q" -> {
                    val bytes = ByteArrayOutputStream()
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
