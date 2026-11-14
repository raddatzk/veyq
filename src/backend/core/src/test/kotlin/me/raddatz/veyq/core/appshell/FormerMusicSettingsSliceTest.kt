package me.raddatz.veyq.core.appshell

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.response.Response
import me.raddatz.veyq.core.appshell.AppShellSliceTest.Companion.CSP
import me.raddatz.veyq.core.appshell.AppShellSliceTest.Companion.FIXTURE_MARKER
import me.raddatz.veyq.core.appshell.AppShellSliceTest.Companion.HTML
import me.raddatz.veyq.core.appshell.AppShellSliceTest.Companion.PERMISSIONS_POLICY
import me.raddatz.veyq.core.appshell.AppShellSliceTest.Companion.SERVED_POLICY
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds
import me.raddatz.veyq.core.platform.capabilities.CapabilitySwitchFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * An instance that ran the retired streaming music (ADR 0131, superseded by ADR 0135 decision 1) and still carries its
 * settings: `:core`'s test task sets every former `VEYQ_MUSIC_*` variable in the test JVM's environment (`build.gradle.kts`)
 * — the release flag on, a music host, a Spotify client ID and an Apple token that has not expired — so no boot of its own,
 * and every boot of the suite proves that a leftover variable refuses no start. What
 * it asserts is that nothing reads them any more: the app's policies are the constants they were before the music, the
 * former music host is a name no tenant claims like any other, and no listing names a `memories.music` id, not even where
 * a switch row for one is left (decision 5 of the CR that removed it: no migration).
 */
@QuarkusTest
class FormerMusicSettingsSliceTest {

    @Test
    fun theAppsPoliciesAreTheConstantsWithEveryFormerMusicVariableSet() {
        val page = html(null, "/recover")
        assertEquals(200, page.statusCode)
        assertTrue(page.body.asString().contains(FIXTURE_MARKER), "control: the page is the shell")
        assertEquals(SERVED_POLICY, page.header(CSP))
        assertTrue("frame-src 'none'" in page.header(CSP), page.header(CSP))
        assertTrue(FORMER_MUSIC_HOST !in page.header(CSP), page.header(CSP))
        assertEquals(PERMISSIONS_POLICY, page.header("Permissions-Policy"))
        assertEquals("same-origin", page.header("Cross-Origin-Opener-Policy"))
    }

    @Test
    fun theFormerMusicHostGetsTheAnswerOfAnyHostNoTenantClaims() {
        for (path in listOf("/music-frame", "/music-frame/spotify-callback", "/music-frame.html", "/recover", "/api/setup")) {
            val former = html(FORMER_MUSIC_HOST, path)
            val unclaimed = html(UNCLAIMED_HOST, path)
            assertEquals(unclaimed.statusCode, former.statusCode, path)
            assertEquals(unclaimed.body.asString(), former.body.asString(), path)
            for (header in listOf(CSP, "Permissions-Policy", "Cross-Origin-Opener-Policy", "Cross-Origin-Resource-Policy")) {
                assertEquals(unclaimed.header(header), former.header(header), "$path: $header")
            }
        }
        // Control: the comparison reads a page, not two empty answers: the former frame's address is the app's shell.
        assertTrue(html(FORMER_MUSIC_HOST, "/music-frame").body.asString().contains(FIXTURE_MARKER))
    }

    @Test
    fun noListingNamesAMusicIdEvenWithASwitchRowForOne() {
        CapabilitySwitchFixtures.with("memories.music", "memories.music.spotify") {
            val setup = given().`when`().get("/api/setup").then().statusCode(200).extract()
            val ids = setup.path<List<String>>("capabilities.id")
            assertEquals(CapabilityIds.ADVERTISED, ids, "the manifest: every entry, and the rows narrow nothing")
            assertTrue(ids.none { it.startsWith("memories.music") }, ids.toString())
            assertTrue(CapabilityIds.MEMORIES in ids, "control: the application itself is served")
            val body = setup.asString()
            assertTrue(FORMER_MUSIC_HOST !in body && "0123456789abcdef0123456789abcdef" !in body, "no former music value on the wire")
            assertTrue("musicReleased" !in body, "the release flag left the setup status")
        }
    }

    private fun html(host: String?, path: String): Response =
        given().let { if (host == null) it else it.header("X-Forwarded-Host", host) }.header("Accept", HTML).`when`().get(path)

    private companion object {
        /** `VEYQ_MUSIC_DOMAIN` in `:core`'s test environment (`build.gradle.kts`). */
        const val FORMER_MUSIC_HOST = "music.veyq.test"
        const val UNCLAIMED_HOST = "unclaimed.veyq.test"
    }
}
