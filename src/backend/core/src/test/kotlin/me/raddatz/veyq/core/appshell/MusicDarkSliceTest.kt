package me.raddatz.veyq.core.appshell

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import me.raddatz.veyq.core.appshell.AppShellSliceTest.Companion.CSP
import me.raddatz.veyq.core.appshell.AppShellSliceTest.Companion.FIXTURE_MARKER
import me.raddatz.veyq.core.appshell.AppShellSliceTest.Companion.HTML
import me.raddatz.veyq.core.appshell.AppShellSliceTest.Companion.PERMISSIONS_POLICY
import me.raddatz.veyq.core.appshell.AppShellSliceTest.Companion.SERVED_POLICY
import me.raddatz.veyq.core.appshell.MusicFrameSliceTest.Companion.CLIENT_ID
import me.raddatz.veyq.core.appshell.MusicFrameSliceTest.Companion.MUSIC_HOST
import me.raddatz.veyq.core.platform.capabilities.MusicDeployment
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Background music released dark (ADR 0131 decision 8, owner 2026-11-13): a deployment that sets the music host and both
 * services' values but not the TEMPORARY release flag `veyq.music.released` (removed with it) — this suite's default profile (`application-test.properties`), so no boot
 * of its own — is what an instance was before ADR 0131: no music entry served and no music value on the wire, the app's
 * policies unchanged (COOP `same-origin`, `frame-src 'none'`, no `encrypted-media` origin), no frame on the music host or
 * any other, no privacy-notice passage. [MusicFrameSliceTest] is the same deployment released (the same host and client
 * ID): each assertion here has its control there, one property apart. The default profile's Apple token is no JWT, so
 * that this suite boots at all proves a dark deployment reads none of the three values.
 */
@QuarkusTest
class MusicDarkSliceTest {

    @Inject
    lateinit var music: MusicDeployment

    @Test
    fun noMusicEntryIsServedAndNoValueReachesAClient() {
        val setup = given().`when`().get("/api/setup").then().statusCode(200).extract()
        val ids = setup.path<List<String>>("capabilities.id")
        assertTrue(ids.none { it.startsWith("memories.music") }, ids.toString())
        assertTrue("memories" in ids, "control: the application itself is served")
        val body = setup.asString()
        assertTrue(MUSIC_HOST !in body && CLIENT_ID !in body && "not-a-jwt" !in body, "no music value on the wire")
        assertNull(music.domain, "every consumer of the music host sees none")
        assertEquals(false, setup.path<Boolean>("musicReleased"), "the web's helper reads the flag off")
    }

    @Test
    fun theAppsPoliciesAreAsBeforeTheMusic() {
        val page = given().header("Accept", HTML).`when`().get("/recover").then().statusCode(200).extract().response()
        assertEquals(SERVED_POLICY, page.header(CSP))
        assertTrue("frame-src 'none'" in page.header(CSP), page.header(CSP))
        assertEquals("same-origin", page.header("Cross-Origin-Opener-Policy"))
        assertEquals(PERMISSIONS_POLICY, page.header("Permissions-Policy"))
    }

    @Test
    fun noHostServesTheFrame() {
        for (host in listOf(MUSIC_HOST, "localhost")) {
            for (path in listOf("/music-frame", "/music-frame/spotify-callback", "/music-frame.html")) {
                given().header("X-Forwarded-Host", host).header("Accept", HTML).`when`().get(path)
                    .then().body(not(containsString("music-frame-fixture")))
            }
        }
        // The music host is no host of its own: it gets the app's answers and policies like any other name.
        given().header("X-Forwarded-Host", MUSIC_HOST).header("Accept", HTML).`when`().get("/recover")
            .then().statusCode(200).body(containsString(FIXTURE_MARKER))
            .header("Cross-Origin-Opener-Policy", "same-origin")
    }

    @Test
    fun thePrivacyNoticeHasNoMusicPassage() {
        val ids = given().`when`().get("/api/operator/privacy-notice").then().statusCode(200).extract().path<List<String>>("passages.id")
        assertTrue("background-music" !in ids, ids.toString())
        assertTrue("controller" in ids, "control: the notice is served")
    }
}
