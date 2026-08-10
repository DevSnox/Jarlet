package me.devsnox.jarlet.lib

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure, network-free coverage for [SemVer]/[pickHighestWithinBound] -- the
 * shared building block behind the `track = "minor"`/`"patch"` auto-update
 * policy (see `prototyping/documentation/concepts/auto-update-policy.md`).
 * This is the main test coverage for that feature: the adapters that
 * consume it (Github/Spiget/Hangar/Paper) have no HTTP-mocking harness in
 * this codebase, same precedent as every other adapter's install path.
 */
class SemVerTest {

    // -- parse --

    @Test
    fun `parses a plain semver string`() {
        assertEquals(SemVer(1, 21, 1), SemVer.parse("1.21.1"))
    }

    @Test
    fun `parses a v-prefixed semver string`() {
        assertEquals(SemVer(1, 2, 3), SemVer.parse("v1.2.3"))
    }

    @Test
    fun `tolerates a trailing build-number style suffix`() {
        assertEquals(SemVer(5, 8, 3579), SemVer.parse("5.8.3579"))
    }

    @Test
    fun `tolerates a trailing pre-release suffix`() {
        assertEquals(SemVer(2, 22, 0), SemVer.parse("2.22.0-SNAPSHOT"))
    }

    @Test
    fun `rejects a non-semver string`() {
        assertNull(SemVer.parse("latest"))
    }

    @Test
    fun `rejects an empty string`() {
        assertNull(SemVer.parse(""))
    }

    @Test
    fun `rejects a two-part version`() {
        assertNull(SemVer.parse("1.21"))
    }

    // -- bounds --

    @Test
    fun `minor track allows a higher minor within the same major`() {
        val baseline = SemVer(1, 21, 1)
        assertTrue(baseline.bounds(SemVer(1, 22, 0), "minor"))
    }

    @Test
    fun `minor track allows a higher patch within the same major`() {
        val baseline = SemVer(1, 21, 1)
        assertTrue(baseline.bounds(SemVer(1, 21, 2), "minor"))
    }

    @Test
    fun `minor track rejects a different major`() {
        val baseline = SemVer(1, 21, 1)
        assertTrue(!baseline.bounds(SemVer(2, 0, 0), "minor"))
    }

    @Test
    fun `patch track allows only a higher patch within the same major minor`() {
        val baseline = SemVer(1, 21, 1)
        assertTrue(baseline.bounds(SemVer(1, 21, 2), "patch"))
    }

    @Test
    fun `patch track rejects a different minor`() {
        val baseline = SemVer(1, 21, 1)
        assertTrue(!baseline.bounds(SemVer(1, 22, 0), "patch"))
    }

    @Test
    fun `patch track rejects a different major`() {
        val baseline = SemVer(1, 21, 1)
        assertTrue(!baseline.bounds(SemVer(2, 21, 1), "patch"))
    }

    @Test
    fun `bounds is inclusive of the baseline itself`() {
        val baseline = SemVer(1, 21, 1)
        assertTrue(baseline.bounds(SemVer(1, 21, 1), "minor"))
        assertTrue(baseline.bounds(SemVer(1, 21, 1), "patch"))
    }

    @Test
    fun `bounds rejects an unrecognized track value`() {
        val baseline = SemVer(1, 21, 1)
        assertTrue(!baseline.bounds(SemVer(1, 21, 2), "latest"))
    }

    // -- compareTo --

    @Test
    fun `compares by major then minor then patch`() {
        assertTrue(SemVer(1, 0, 0) < SemVer(2, 0, 0))
        assertTrue(SemVer(1, 1, 0) < SemVer(1, 2, 0))
        assertTrue(SemVer(1, 1, 1) < SemVer(1, 1, 2))
        assertEquals(0, SemVer(1, 1, 1).compareTo(SemVer(1, 1, 1)))
    }

    // -- pickHighestWithinBound --

    @Test
    fun `picks the highest candidate within bound, ignoring unparseable ones`() {
        val baseline = SemVer(1, 21, 1)
        val candidates = listOf("1.21.1", "1.21.2", "not-semver", "1.22.0", "2.0.0")

        val picked = pickHighestWithinBound(candidates, { it }, baseline, "minor")

        assertEquals("1.22.0", picked)
    }

    @Test
    fun `patch track picks the highest patch only, excluding a higher minor`() {
        val baseline = SemVer(1, 21, 1)
        val candidates = listOf("1.21.1", "1.21.5", "1.22.0")

        val picked = pickHighestWithinBound(candidates, { it }, baseline, "patch")

        assertEquals("1.21.5", picked)
    }

    @Test
    fun `returns null when nothing qualifies`() {
        val baseline = SemVer(1, 21, 1)
        val candidates = listOf("2.0.0", "3.0.0", "not-semver")

        val picked = pickHighestWithinBound(candidates, { it }, baseline, "minor")

        assertNull(picked)
    }

    @Test
    fun `returns null when every candidate is unparseable`() {
        val baseline = SemVer(1, 21, 1)
        val candidates = listOf("latest", "", "channel-release")

        val picked = pickHighestWithinBound(candidates, { it }, baseline, "minor")

        assertNull(picked)
    }

    @Test
    fun `a tie between equal versions picks either -- deterministic result either way`() {
        data class Candidate(val label: String, val version: String)

        val baseline = SemVer(1, 0, 0)
        val candidates = listOf(Candidate("a", "1.2.3"), Candidate("b", "1.2.3"))

        val picked = pickHighestWithinBound(candidates, { it.version }, baseline, "minor")

        assertTrue(picked != null && picked.version == "1.2.3")
    }
}
