package me.raddatz.veyq.core.admin.instance

import me.raddatz.veyq.core.adminchannel.givenAdminChannel
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.ensureUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

/**
 * The release check on an instance that **is** stamped — the profile [InstanceReleaseSliceTest] already boots — so the
 * scheduled entry point itself, with the instance's own release, is what asks the source; [ReleaseCheckSliceTest]'s
 * unstamped no-request has its positive control here. And the console shows a notice only while its release is newer
 * than the running one.
 */
@QuarkusTest
@TestProfile(InstanceReleaseSliceTest.Stamped::class)
class ReleaseCheckStampedSliceTest {

    @Inject
    lateinit var check: ReleaseCheck

    @Inject
    lateinit var store: ReleaseNoticeStore

    private lateinit var source: FakeReleaseSource

    @BeforeEach
    fun seed() {
        ensureUser(OWNER_ID, OWNER_EMAIL, "SERVER_OWNER")
        // The owner's auth key is this class's real one, so [signIn] can sign the login challenge.
        QuarkusTransaction.requiringNew().run {
            VeyqUser.findById(OWNER_ID)!!.authPublicKey = Base64.getEncoder().encodeToString(AUTH_KEYS.public.encoded.takeLast(32).toByteArray())
        }
        source = FakeReleaseSource()
        ReleaseCheckSliceTest.reset()
        store.saveSetting(enabled = true, source = "${source.base}/m")
    }

    @AfterEach
    fun restore() {
        source.close()
        ReleaseCheckSliceTest.reset()
    }

    @Test
    fun theScheduledRunAsksTheSourceWithTheInstancesOwnRelease() {
        source.mirror("/m", listOf("v77.12" to "2027-01-01", "v77.13" to "2027-02-01", "v77.14" to "2027-03-01"))

        check.run()

        assertTrue(source.seen.isNotEmpty(), "a stamped instance asks its source")
        assertTrue(source.seen.none { it.mentions(InstanceReleaseSliceTest.STAMPED_RELEASE) }, "and names no release of its own")
        assertEquals(listOf("77.14"), store.notices().map { it.release.toString() })
    }

    @Test
    fun theConsoleShowsANoticeOnlyWhileItsReleaseIsNewerThanTheRunningOne() {
        // As if found while an older release ran: one is behind the running release now, one is still ahead.
        source.mirror("/m", listOf("v77.12" to "2027-01-01", "v77.13" to "2027-02-01", "v77.14" to "2027-03-01"))
        check.check("77.11", ReleaseCheckSliceTest.NOW)
        assertEquals(setOf("77.12", "77.13", "77.14"), store.notices().map { it.release.toString() }.toSet())

        givenAdminChannel().header("Authorization", bearer(OWNER_ID))
            .`when`().get("/api/instance/release/notices").then().statusCode(200)
            .body("releaseLine", equalTo(true))
            .body("notices.release", equalTo(listOf("77.14")))
    }

    /**
     * Neither a sign-in nor a read of the setting starts the back-off over: only a success, a changed setting or a
     * restart does. The proof run of 2026-10-24 saw the series restart at 5 s during a stage's sign-ins — those were
     * `measure_volume.py`'s own app restarts (its cold calls), but nothing pinned that the requests around them could
     * not do it too. Each tick here is one call of the scheduled entry point; the source answers every read 404.
     */
    @Test
    fun aSignInOrASettingsReadLeavesTheBackOffWhereItWas() {
        // Ticks 1, 2 and 4 ask (waits of 1, 2, then 4 ticks): three failures in a row, the next attempt at tick 8.
        assertEquals("1101", ticks(4))

        signIn()
        givenAdminChannel().header("Authorization", bearer(OWNER_ID))
            .`when`().get("/api/instance/release/notices").then().statusCode(200)
            .body("source", equalTo("${source.base}/m"))
        store.setting()

        // Unbroken: ticks 5–7 still wait, tick 8 asks. A reset would have asked at tick 5.
        assertEquals("0001", ticks(4))
    }

    /** [n] ticks of the scheduled run, as 1 (it asked the source) or 0 (it waited). */
    private fun ticks(n: Int): String = (1..n).joinToString("") {
        val before = source.seen.size
        check.run()
        if (source.seen.size > before) "1" else "0"
    }

    /** A real sign-in of the Server Owner, challenge → signature → session, as a browser makes it. */
    private fun signIn() {
        val nonce: String = given().contentType(ContentType.JSON).body("""{"email":"$OWNER_EMAIL"}""")
            .`when`().post("/api/login/challenge").then().statusCode(200).extract().path("nonce")
        val signature = Signature.getInstance("Ed25519").run {
            initSign(AUTH_KEYS.private)
            update(Base64.getDecoder().decode(nonce))
            Base64.getEncoder().encodeToString(sign())
        }
        given().contentType(ContentType.JSON)
            .body("""{"email":"$OWNER_EMAIL","nonce":"$nonce","signature":"$signature"}""")
            .`when`().post("/api/login/verify").then().statusCode(200).body("accessToken", notNullValue())
    }

    private companion object {
        const val OWNER_ID = "c3e5a7b9-1d2f-4a6c-8e0b-2d4f6a8c0e13"
        const val OWNER_EMAIL = "release-stamped-owner@example.com"
        val AUTH_KEYS: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    }
}
