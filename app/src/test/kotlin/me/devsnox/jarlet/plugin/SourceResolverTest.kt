package me.devsnox.jarlet.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import me.devsnox.jarlet.config.JarletToml

/**
 * [SourceResolver.resolveDeclaredIdentifier] coverage -- entirely
 * network-free (unlike [SourceResolver.resolveAddIdentifier], this
 * function only ever consults the already-parsed `[[plugins]]` list, never
 * an adapter's API). Focuses on the github repo-name shorthand added
 * alongside `GithubAdapter`'s `displayName` caching (see
 * [me.devsnox.jarlet.adapter.plugin.GithubAdapter]'s `repoNameOf` /
 * `InstalledVersion.displayName` write): a user typing the bare repo name
 * (e.g. `"Essentials"`) should resolve a declared `"EssentialsX/Essentials"`
 * github entry, without that shorthand ever leaking into non-github
 * matching or overriding an exact full-id match.
 */
class SourceResolverTest {

    private fun toml(vararg plugins: JarletToml.Plugin) = JarletToml(
        template = JarletToml.Template(name = "test-template"),
        server = JarletToml.Server(
            pkg = "paper",
            minecraftVersion = "1.21.1",
            memory = "2G",
            port = 25565,
            onlineMode = true,
        ),
        plugins = plugins.toList(),
    )

    @Test
    fun `full owner-repo id resolves unchanged`() {
        val t = toml(JarletToml.Plugin(source = "github", id = "EssentialsX/Essentials"))

        val resolved = SourceResolver.resolveDeclaredIdentifier(t, "EssentialsX/Essentials", "remove")

        assertEquals(SourceResolver.Resolved("github", "EssentialsX/Essentials"), resolved)
    }

    @Test
    fun `bare repo name shorthand resolves a declared github entry`() {
        val t = toml(JarletToml.Plugin(source = "github", id = "EssentialsX/Essentials"))

        val resolved = SourceResolver.resolveDeclaredIdentifier(t, "Essentials", "remove")

        assertEquals(SourceResolver.Resolved("github", "EssentialsX/Essentials"), resolved)
    }

    @Test
    fun `bare repo name shorthand is case-insensitive`() {
        val t = toml(JarletToml.Plugin(source = "github", id = "EssentialsX/Essentials"))

        val resolved = SourceResolver.resolveDeclaredIdentifier(t, "essentials", "update")

        assertEquals(SourceResolver.Resolved("github", "EssentialsX/Essentials"), resolved)
    }

    @Test
    fun `non-github source still requires an exact id match`() {
        val t = toml(JarletToml.Plugin(source = "hangar", id = "Geyser"))

        assertFailsWith<SourceResolver.ResolutionException> {
            SourceResolver.resolveDeclaredIdentifier(t, "eyser", "remove")
        }
    }

    @Test
    fun `spiget bare numeric id is unaffected by the github shorthand path`() {
        val t = toml(JarletToml.Plugin(source = "spiget", id = "12345"))

        val resolved = SourceResolver.resolveDeclaredIdentifier(t, "12345", "remove")

        assertEquals(SourceResolver.Resolved("spiget", "12345"), resolved)
    }

    @Test
    fun `unmatched identifier still throws not-found`() {
        val t = toml(JarletToml.Plugin(source = "github", id = "EssentialsX/Essentials"))

        val ex = assertFailsWith<SourceResolver.ResolutionException> {
            SourceResolver.resolveDeclaredIdentifier(t, "NotDeclared", "remove")
        }
        assertEquals("No declared plugin with id 'NotDeclared' (for remove)", ex.message)
    }

    @Test
    fun `exact-id match still wins over the shorthand path when both could apply`() {
        // A declared id that happens to equal the identifier exactly takes
        // the first (exact-match) branch, never falling through to the
        // repo-name shorthand at all -- this is really just documenting
        // that the shorthand only kicks in once the exact-match pass finds
        // nothing.
        val t = toml(
            JarletToml.Plugin(source = "github", id = "Essentials"),
            JarletToml.Plugin(source = "github", id = "EssentialsX/Essentials"),
        )

        val resolved = SourceResolver.resolveDeclaredIdentifier(t, "Essentials", "remove")

        assertEquals(SourceResolver.Resolved("github", "Essentials"), resolved)
    }

    @Test
    fun `repo name shorthand matching more than one declared github entry is an internal consistency error`() {
        val t = toml(
            JarletToml.Plugin(source = "github", id = "EssentialsX/Essentials"),
            JarletToml.Plugin(source = "github", id = "OtherOwner/Essentials"),
        )

        val ex = assertFailsWith<SourceResolver.ResolutionException> {
            SourceResolver.resolveDeclaredIdentifier(t, "Essentials", "remove")
        }
        assertTrue(ex.message!!.contains("declared under more than one source"))
    }
}
