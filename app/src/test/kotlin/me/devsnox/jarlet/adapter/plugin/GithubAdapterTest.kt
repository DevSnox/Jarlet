package me.devsnox.jarlet.adapter.plugin

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers [GithubAdapter.repoNameOf], the pure id-shape helper behind the
 * `InstalledVersion.displayName` value `GithubAdapter` now writes on every
 * install/update (see its call site's doc comment). Deliberately scoped to
 * just this helper rather than the full `install()`/`update()` flow: those
 * methods only ever talk to a live GitHub API and this codebase has no
 * HTTP-mocking harness for adapters yet (no other adapter -- Hangar,
 * Spiget -- has install-path test coverage either, for the same reason).
 * `repoNameOf` is exactly the piece of that flow this task changed, so it's
 * exercised directly.
 */
class GithubAdapterTest {

    @Test
    fun `repo name is the part after the last slash`() {
        assertEquals("Essentials", GithubAdapter.repoNameOf("EssentialsX/Essentials"))
    }

    @Test
    fun `repo name extraction is not case-normalized`() {
        assertEquals("ViaVersion", GithubAdapter.repoNameOf("ViaVersion/ViaVersion"))
    }
}
