package me.raddatz.veyq.core.appshell

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import me.raddatz.veyq.core.adminchannel.givenAdminChannel
import me.raddatz.veyq.core.appshell.AppShellSliceTest.Companion.CSP
import me.raddatz.veyq.core.appshell.AppShellSliceTest.Companion.FIXTURE_MARKER
import me.raddatz.veyq.core.appshell.AppShellSliceTest.Companion.HTML
import me.raddatz.veyq.core.appshell.AppShellSliceTest.Companion.PERMISSIONS_POLICY
import me.raddatz.veyq.core.appshell.AppShellSliceTest.Companion.SERVED_POLICY
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds
import me.raddatz.veyq.core.platform.capabilities.CapabilitySwitchFixtures
import me.raddatz.veyq.core.platform.capabilities.ExternalServiceOrigins
import me.raddatz.veyq.core.platform.capabilities.MusicFrameHosts
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.UUID

/**
 * The music frame on a deployment that configures it (ADR 0131 decisions 2 and 4): the register's three entries with their
 * values, the app's policies while `memories.music` is active and back when the Server Owner switches it off, and the
 * music host's whole surface — the frame's page and the assets it loads under the frame's own policy, a 404 for the API
 * and the app. Every negative has its control on the same request with one thing changed (the host, the switch). The
 * unconfigured deployment is every other test class's profile (`CapabilityManifestSliceTest` reads the entries off).
 */
@QuarkusTest
@TestProfile(MusicFrameSliceTest.MusicConfigured::class)
class MusicFrameSliceTest {

    class MusicConfigured : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf(
            "veyq.music.domain" to MUSIC_HOST,
            "veyq.music.spotify-client-id" to CLIENT_ID,
            "veyq.music.apple-developer-token" to APPLE_TOKEN,
            "veyq.tenant.ingress.bootstrap-domain" to BOOTSTRAP,
        )
    }

    @Inject
    lateinit var origins: ExternalServiceOrigins

    @Inject
    lateinit var musicHosts: MusicFrameHosts

    @AfterEach
    fun switchEverythingBackOn() {
        CapabilitySwitchFixtures.clear()
        origins.refresh()
    }

    @Test
    fun theThreeEntriesAreServedWithTheirValuesOnTheirOwnEntriesOnly() {
        @Suppress("UNCHECKED_CAST")
        val caps = given().`when`().get("/api/setup").then().statusCode(200).extract().path<List<Map<String, Any?>>>("capabilities")
        val byId = caps.associateBy { it["id"] }
        assertEquals("https://$MUSIC_HOST/music-frame", byId[CapabilityIds.MEMORIES_MUSIC]?.get("serviceUrl"))
        assertEquals(CLIENT_ID, byId[CapabilityIds.MEMORIES_MUSIC_SPOTIFY]?.get("spotifyClientId"))
        assertEquals(APPLE_TOKEN, byId[CapabilityIds.MEMORIES_MUSIC_APPLE_MUSIC]?.get("appleDeveloperToken"))
        caps.filter { it["id"] != CapabilityIds.MEMORIES_MUSIC_SPOTIFY }.forEach { assertEquals(null, it["spotifyClientId"], "${it["id"]}") }
        caps.filter { it["id"] != CapabilityIds.MEMORIES_MUSIC_APPLE_MUSIC }.forEach { assertEquals(null, it["appleDeveloperToken"], "${it["id"]}") }
    }

    @Test
    fun whileActiveTheAppMayFrameTheMusicHostAndItsPopupsKeepTheirOpener() {
        val page = appPage()
        assertEquals(SERVED_POLICY.replace("frame-src 'none'", "frame-src $MUSIC_ORIGIN"), page.header(CSP))
        assertEquals("same-origin-allow-popups", page.header("Cross-Origin-Opener-Policy"))
        assertEquals(
            PERMISSIONS_POLICY.replace("encrypted-media=()", "encrypted-media=(\"$MUSIC_ORIGIN\")")
                .replace("autoplay=(self)", "autoplay=(self \"$MUSIC_ORIGIN\")"),
            page.header("Permissions-Policy"),
        )
    }

    @Test
    fun switchedOffByTheServerOwnerThePoliciesAreAsBeforeAndTheEntriesLeaveTheSet() {
        instanceSwitch(off = true)
        val page = appPage()
        assertEquals(SERVED_POLICY, page.header(CSP))
        assertEquals("same-origin", page.header("Cross-Origin-Opener-Policy"))
        assertEquals(PERMISSIONS_POLICY, page.header("Permissions-Policy"))
        val ids = given().`when`().get("/api/setup").then().statusCode(200).extract().path<List<String>>("capabilities.id")
        assertTrue(CapabilityIds.MEMORIES_MUSIC !in ids && CapabilityIds.MEMORIES_MUSIC_SPOTIFY !in ids, ids.toString())
        // Control: back on, back in.
        instanceSwitch(off = false)
        assertEquals("same-origin-allow-popups", appPage().header("Cross-Origin-Opener-Policy"))
    }

    @Test
    fun theMusicHostServesTheFrameUnderItsOwnPolicyFramedOnlyByTheInstancesHosts() {
        val tenant = "music-${UUID.randomUUID()}.example.com"
        seedTenant(domain = tenant, owner = seedUser())
        musicHosts.refresh() // what `TenantHandler`'s reproject does; a seeded row bypasses it
        for (path in listOf("/music-frame", "/music-frame/spotify-callback")) {
            val frame = onMusicHost().`when`().get(path)
            frame.then().statusCode(200).body(containsString("music-frame-fixture"))
            val policy = frame.header(CSP)
            assertTrue(policy.contains("script-src 'self' https://sdk.scdn.co https://js-cdn.music.apple.com;"), policy)
            // The suite's database holds other tests' tenants too: every one of them is an ancestor, these two among them.
            val ancestors = policy.substringAfter("frame-ancestors ").split(' ')
            assertTrue(BOOTSTRAP in ancestors && tenant in ancestors && MUSIC_HOST !in ancestors, policy)
            assertTrue("require-trusted-types-for" !in policy, policy)
            assertEquals("unsafe-none", frame.header("Cross-Origin-Opener-Policy"))
            assertTrue(frame.header("Permissions-Policy").startsWith("encrypted-media=(self \"https://sdk.scdn.co\")"))
        }
        onMusicHost().`when`().get("/assets/app-fixture.js").then().statusCode(200)
    }

    @Test
    fun theMusicHostAnswersNoApiAndNoApp() {
        onMusicHost().`when`().get("/api/setup").then().statusCode(404)
        onMusicHost().header("Accept", HTML).`when`().get("/recover").then().statusCode(404)
        onMusicHost().`when`().get("/music-frame.html").then().statusCode(404)
        // Controls: the same requests on the app's host.
        given().`when`().get("/api/setup").then().statusCode(200)
        appPage().then().body(containsString(FIXTURE_MARKER))
    }

    @Test
    fun noOtherHostServesTheFramesPage() {
        given().header("Accept", HTML).`when`().get("/music-frame.html").then().statusCode(404)
        given().header("X-Forwarded-Host", BOOTSTRAP).header("Accept", HTML).`when`().get("/music-frame.html").then().statusCode(404)
        // Control: the music host serves it, at its own address.
        onMusicHost().`when`().get("/music-frame").then().statusCode(200)
    }

    @Test
    fun thePrivacyNoticeStatesTheMusicWhileItIsOnAndNotAfter() {
        val ids = { given().`when`().get("/api/operator/privacy-notice").then().statusCode(200).extract().path<List<String>>("passages.id") }
        assertTrue("background-music" in ids(), "configured and on: the passage says what the service learns")
        instanceSwitch(off = true)
        assertTrue("background-music" !in ids(), "switched off: nothing to state")
        assertTrue("controller" in ids(), "control: the notice is still served")
    }

    private fun onMusicHost() = given().header("X-Forwarded-Host", MUSIC_HOST)

    private fun appPage() = given().header("Accept", HTML).`when`().get("/recover").then().statusCode(200).extract().response()

    private fun instanceSwitch(off: Boolean) {
        givenAdminChannel().header("Authorization", bearer(seedUser(serverRole = "SERVER_OWNER"))).contentType(ContentType.JSON)
            .body("""{"off":$off}""").`when`().put("/api/instance/capabilities/${CapabilityIds.MEMORIES_MUSIC}").then().statusCode(200)
    }

    companion object {
        const val MUSIC_HOST = "music.veyq.test"
        const val MUSIC_ORIGIN = "https://$MUSIC_HOST"
        const val BOOTSTRAP = "veyq.veyq.test"
        const val CLIENT_ID = "0123456789abcdef0123456789abcdef"

        private fun b64(json: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

        /** A token whose `exp` is 2100-01-01: configured, and active for the whole life of this test. */
        val APPLE_TOKEN = "${b64("""{"alg":"ES256","kid":"KEY123"}""")}.${b64("""{"iss":"TEAM123","iat":1700000000,"exp":4102444800}""")}.c2ln"
    }
}
