package me.devsnox.jarlet.config

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure, network-free coverage for [SelfUpdateChecker.evaluate] -- the
 * notify-only "newer release available" decision, with no HTTP/threading
 * involved.
 */
class SelfUpdateCheckerTest {

    @Test
    fun `evaluate returns null when the latest tag is not newer than the current version`() {
        val notice = SelfUpdateChecker.evaluate(
            currentVersion = "0.2.0",
            latestTag = "0.2.0",
            assetNames = setOf("jarlet-0.2.0-linux-x86_64"),
            installUrl = "https://example.com/install.sh",
        )

        assertNull(notice)
    }

    @Test
    fun `evaluate returns null when the current version is newer than the latest tag`() {
        val notice = SelfUpdateChecker.evaluate(
            currentVersion = "0.3.0",
            latestTag = "0.2.0",
            assetNames = setOf("jarlet-0.2.0-linux-x86_64"),
            installUrl = "https://example.com/install.sh",
        )

        assertNull(notice)
    }

    @Test
    fun `evaluate returns null when the newer release has no matching asset published`() {
        val notice = SelfUpdateChecker.evaluate(
            currentVersion = "0.2.0",
            latestTag = "0.3.0",
            assetNames = emptySet(),
            installUrl = "https://example.com/install.sh",
        )

        assertNull(notice)
    }

    @Test
    fun `evaluate returns the notice when a newer release exists with its asset published`() {
        val installUrl = "https://example.com/install.sh"

        val notice = SelfUpdateChecker.evaluate(
            currentVersion = "0.2.0",
            latestTag = "0.3.0",
            assetNames = setOf("jarlet-0.3.0-linux-x86_64"),
            installUrl = installUrl,
        )

        assertTrue(notice != null)
        assertTrue(notice.contains("0.3.0"))
        assertTrue(notice.contains(installUrl))
    }

    @Test
    fun `evaluate ignores a current version's prerelease suffix`() {
        val notice = SelfUpdateChecker.evaluate(
            currentVersion = "0.2.0-alpha.2",
            latestTag = "0.2.0",
            assetNames = setOf("jarlet-0.2.0-linux-x86_64"),
            installUrl = "https://example.com/install.sh",
        )

        assertNull(notice)
    }
}
