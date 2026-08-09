package me.devsnox.jarlet.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Regression coverage for `SharedHttp.contentDispositionFileName()` --
 * specifically the RFC 5987 `filename*=` decode path, which had a real bug:
 * a capture-group regex that excluded `'` truncated GeyserMC's real header
 * value (`filename*=UTF-8''Geyser-Spigot.jar`) down to just `"UTF-8"` at the
 * first quote, since RFC 5987's `charset'lang'value` format is delimited by
 * that exact character.
 */
class SharedHttpTest {

    @Test
    fun `decodes GeyserMC's real Content-Disposition header via the RFC 5987 filename star parameter`() {
        val header = """attachment; filename="=?UTF-8?Q?Geyser-Spigot.jar?="; filename*=UTF-8''Geyser-Spigot.jar"""
        assertEquals("Geyser-Spigot.jar", SharedHttp.contentDispositionFileName(header))
    }

    @Test
    fun `decodes a bare RFC 5987 filename star parameter with no language tag`() {
        assertEquals(
            "Geyser-Spigot.jar",
            SharedHttp.contentDispositionFileName("attachment; filename*=UTF-8''Geyser-Spigot.jar"),
        )
    }

    @Test
    fun `decodes a percent-encoded RFC 5987 value`() {
        assertEquals(
            "my file.jar",
            SharedHttp.contentDispositionFileName("attachment; filename*=UTF-8''my%20file.jar"),
        )
    }

    @Test
    fun `falls back to decoding an RFC 2047 encoded-word in a plain filename when no filename star is present`() {
        assertEquals(
            "Geyser-Spigot.jar",
            SharedHttp.contentDispositionFileName("""attachment; filename="=?UTF-8?Q?Geyser-Spigot.jar?=""""),
        )
    }

    @Test
    fun `returns a plain filename unchanged when neither encoding is present`() {
        assertEquals(
            "EssentialsX-2.22.0.jar",
            SharedHttp.contentDispositionFileName("""attachment; filename="EssentialsX-2.22.0.jar""""),
        )
    }

    @Test
    fun `returns null for a missing header`() {
        assertNull(SharedHttp.contentDispositionFileName(null))
    }
}
