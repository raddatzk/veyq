package com.veyq.app.features.account.login

import com.veyq.app.shared.identity.VeyqUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

/**
 * Slice tests for challenge-response login + sessions (uc.account.login). Seeds a user with a REAL
 * Ed25519 auth keypair directly (the setup endpoint stores a dummy key and is single-owner-locked), then
 * drives challenge → sign → verify and the token lifecycle through the running instance.
 */
@QuarkusTest
class LoginSliceTest {

    @BeforeEach
    fun seedUser() {
        QuarkusTransaction.requiringNew().run(Runnable {
            if (VeyqUser.find("email", EMAIL).firstResult() == null) {
                VeyqUser().apply {
                    id = "login-test-user"
                    email = EMAIL
                    serverRole = "SERVER_OWNER"
                    suiteId = "v1"
                    identityPublicKey = "aWRlbnRpdHktcHVibGljLWtleQ=="
                    wrappedIdentityPrivateKey = "d3JhcHBlZC1pZGVudGl0eS1wcml2YXRlLWtleQ=="
                    authPublicKey = b64(rawPublicKey(KEYPAIR))
                    createdAt = Instant.now()
                }.persist()
            }
        })
    }

    // --- login (challenge → verify) ---

    @Test
    fun validLoginIssuesSession() {
        val nonce = requestChallenge(EMAIL)
        verify(EMAIL, sign(KEYPAIR, nonce))
            .then().statusCode(200)
            .body("accessToken", notNullValue())
            .body("refreshToken", notNullValue())
            .body("family.id", notNullValue())
    }

    @Test
    fun wrongSignatureIsGenericFailure() {
        val nonce = requestChallenge(EMAIL)
        verify(EMAIL, sign(OTHER_KEYPAIR, nonce)) // signed by the wrong key
            .then().statusCode(401).body("code", equalTo("authentication_failed"))
    }

    @Test
    fun unknownAccountIsIdenticalGenericFailure() {
        // Challenge for a non-existent account still returns 200 with a nonce (non-enumerable)...
        val nonce = requestChallenge("ghost@example.com")
        assertTrue(nonce.isNotEmpty())
        // ...and verify fails with the SAME generic code as a wrong signature.
        verify("ghost@example.com", sign(KEYPAIR, nonce))
            .then().statusCode(401).body("code", equalTo("authentication_failed"))
    }

    @Test
    fun replayedNonceIsRejected() {
        val nonce = requestChallenge(EMAIL)
        val sig = sign(KEYPAIR, nonce)
        verify(EMAIL, sig).then().statusCode(200) // single use consumes the challenge
        verify(EMAIL, sig).then().statusCode(401) // replay → no outstanding nonce
    }

    @Test
    fun concurrentVerifyConsumesTheChallengeOnce() {
        // Two requests race the SAME signed challenge. Single-use must be ATOMIC: exactly one issues a session,
        // the other loses the consume and gets a generic 401. Without the row-lock both reads see the nonce
        // before either delete lands, so one captured signature would mint two sessions in parallel (P2f).
        val nonce = requestChallenge(EMAIL)
        val sig = sign(KEYPAIR, nonce)
        val barrier = java.util.concurrent.CyclicBarrier(2)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val codes = pool.invokeAll(
                (0 until 2).map {
                    java.util.concurrent.Callable {
                        barrier.await() // release both requests as tightly together as possible
                        verify(EMAIL, sig).then().extract().statusCode()
                    }
                },
            ).map { it.get() }
            assertEquals(1, codes.count { it == 200 }, "exactly one concurrent verify may consume the challenge, got $codes")
            assertEquals(1, codes.count { it == 401 }, "the losing concurrent verify must get a generic 401, got $codes")
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun expiredNonceIsRejected() {
        val nonce = requestChallenge(EMAIL)
        // Force the stored challenge past its TTL (5 min) without waiting it out — a correctly-signed but
        // stale nonce must be rejected, not just a replayed one.
        QuarkusTransaction.requiringNew().run(Runnable {
            val challenge = LoginChallengeEntity.find("email", EMAIL).firstResult()!!
            challenge.expiresAt = Instant.now().minus(1, ChronoUnit.MINUTES)
            challenge.persist()
        })
        verify(EMAIL, sign(KEYPAIR, nonce)) // valid signature, expired challenge
            .then().statusCode(401).body("code", equalTo("authentication_failed"))
    }

    // --- boundary validation (uc.account.login/boundary-validation) ---

    @Test
    fun malformedRequestsRejectedAtTheBoundary() {
        // The spec's x-field-extra-annotation makes @Valid reject malformed input with 400 BEFORE the handler;
        // these are shape violations, so the response is the container's 400 (no veyq {code}, matching the
        // .http boundary surface this replaces — cr-0159/ADR 0040 drops http as a coverage surface).
        // Missing email on the challenge → @NotBlank.
        given().contentType(ContentType.JSON).body("{}")
            .`when`().post("/api/login/challenge")
            .then().statusCode(400)
        // Malformed email on the challenge → @Email.
        given().contentType(ContentType.JSON).body("""{"email":"not-an-email"}""")
            .`when`().post("/api/login/challenge")
            .then().statusCode(400)
        // Non-base64 signature on verify → @Pattern, rejected before any nonce lookup.
        given().contentType(ContentType.JSON).body("""{"email":"$EMAIL","signature":"not base64!!"}""")
            .`when`().post("/api/login/verify")
            .then().statusCode(400)
    }

    // --- token rotation + reuse detection ---

    @Test
    fun refreshRotatesAndChains() {
        val r1 = login().refreshToken
        val r2 = refresh(r1).then().statusCode(200).extract().path<String>("refreshToken")
        refresh(r2).then().statusCode(200) // the chain keeps rotating
    }

    @Test
    fun replayedSupersededRefreshRevokesFamily() {
        // Two generations behind, deliberately: at ONE generation behind the presenter is a client whose
        // successor never reached it, and ADR 0004's one-generation grace rotates for it. This test is about
        // the ordinary replay, so the chain runs far enough that r1 cannot be mistaken for that case.
        val r1 = login().refreshToken
        val r2 = refresh(r1).then().statusCode(200).extract().path<String>("refreshToken")
        val r3 = refresh(r2).then().statusCode(200).extract().path<String>("refreshToken")
        // r1 is now two generations behind; replaying it is a clone signal → the whole family is revoked.
        refresh(r1).then().statusCode(401)
        // ...so even the current r3 no longer works.
        refresh(r3).then().statusCode(401)
    }

    // --- the one-generation grace (ADR 0004 § the one-generation grace) ---

    @Test
    fun refreshAfterALostResponseRotatesInsteadOfRevoking() {
        // The rotation commits server-side even when its response never reaches the client, so the client's
        // cookie still holds the PREVIOUS token. Presenting it again is the commonest benign case there is —
        // one dropped response used to end the session permanently.
        val r1 = login().refreshToken
        refresh(r1).then().statusCode(200) // the successor is minted and then lost in transit
        val recovered = refresh(r1).then().statusCode(200).extract().path<String>("refreshToken")
        assertEquals("ACTIVE", familyStatusOf(r1), "a lost response must not cost the family")
        refresh(recovered).then().statusCode(200) // and the recovered chain keeps rotating
    }

    @Test
    fun concurrentRefreshOfOneTokenKeepsTheFamily() {
        // Three authed calls that 401 together each fire their own refresh with the same cookie. The row lock
        // serializes them; the grace is what keeps the two losers from reading as clones.
        val r1 = login().refreshToken
        val barrier = java.util.concurrent.CyclicBarrier(3)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
        try {
            val codes = pool.invokeAll(
                (0 until 3).map {
                    java.util.concurrent.Callable {
                        barrier.await()
                        refresh(r1).then().extract().statusCode()
                    }
                },
            ).map { it.get() }
            assertEquals("ACTIVE", familyStatusOf(r1), "concurrent refreshes of one token must not revoke, got $codes")
            assertEquals(0, codes.count { it == 401 }, "no concurrent presentation of a live token may be read as a clone, got $codes")
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun graceDoesNotIncrementSoTheNextStaleCallerIsStillAtDistanceOne() {
        // The regression test for the shape of the grace. If it MINTED A HIGHER generation, the first stale
        // presentation would pass and the second would sit two behind and revoke — so the rule would collapse
        // at three concurrent callers, which is the case it exists for. Re-minting under the orphan's own
        // number keeps every further presentation of one stale token at distance 1.
        val r1 = login().refreshToken
        refresh(r1).then().statusCode(200) // successor minted, lost
        refresh(r1).then().statusCode(200) // first stale caller — grace
        refresh(r1).then().statusCode(200) // second stale caller — an incrementing grace fails HERE
        assertEquals("ACTIVE", familyStatusOf(r1))
    }

    @Test
    fun theOrphanedSuccessorIsAClone() {
        // Distance ZERO, the detector the grace brings with it: whoever holds the successor a grace rotation
        // replaced is the one party that must not be tolerated, and it is exactly the position an attacker who
        // raced the legitimate client ends up in.
        val r1 = login().refreshToken
        val orphan = refresh(r1).then().statusCode(200).extract().path<String>("refreshToken")
        refresh(r1).then().statusCode(200) // grace re-mints under the orphan's own generation
        refresh(orphan).then().statusCode(401)
        assertEquals("REVOKED", familyStatusOf(r1), "the orphaned successor is a clone signal")
    }

    @Test
    fun unknownRefreshTokenIsPlain401AndRevokesNobody() {
        // A live, innocent refresh family exists alongside the attack.
        val victim = login()
        // A token that matches no stored hash is just rejected...
        refresh("not-a-real-token").then().statusCode(401).body("code", equalTo("refresh_rejected"))
        // ...and crucially revokes nobody: the bystander family still refreshes. A bogus token cannot be a
        // DoS lever against other sessions.
        refresh(victim.refreshToken).then().statusCode(200)
    }

    @Test
    fun logoutRevokesFamily() {
        val session = login()
        given().header("Authorization", "Bearer ${session.accessToken}")
            .`when`().post("/api/logout")
            .then().statusCode(204)
        refresh(session.refreshToken).then().statusCode(401) // family gone
    }

    // --- "remember me" cookie transport (cr-0052) ---

    @Test
    fun rememberLoginSetsHttpOnlyRefreshCookieAndOmitsBodyToken() {
        val nonce = requestChallenge(EMAIL)
        val res = verifyRemember(EMAIL, sign(KEYPAIR, nonce)).then().statusCode(200)
            .body("accessToken", notNullValue())
            .body("refreshToken", nullValue()) // omitted from the body — it rides the cookie instead
            .cookie("veyq_refresh", notNullValue())
            .extract()
        assertTrue(res.detailedCookie("veyq_refresh").isHttpOnly)
    }

    @Test
    fun sessionScopeSetsSessionCookieWithoutMaxAge() {
        // Web default "off" (cr-0055): SESSION → a session cookie (no Max-Age, name veyq_refresh_session) so it
        // survives reloads but is dropped on browser close; the token still rides the cookie, not the body.
        val nonce = requestChallenge(EMAIL)
        val res = verifyScope(EMAIL, sign(KEYPAIR, nonce), "SESSION").then().statusCode(200)
            .body("accessToken", notNullValue())
            .body("refreshToken", nullValue())
            .cookie("veyq_refresh_session", notNullValue())
            .cookie("veyq_refresh", equalTo("")) // clears any prior persistent cookie so it can't shadow this one
            .extract()
        val c = res.detailedCookie("veyq_refresh_session")
        assertTrue(c.isHttpOnly)
        // A session cookie must OMIT Max-Age entirely. A literal `Max-Age=-1` (or any negative) makes browsers
        // treat the cookie as already-expired and drop it — so the raw Set-Cookie header must carry no Max-Age.
        val raw = res.headers().getValues("Set-Cookie").first { it.startsWith("veyq_refresh_session=") }
        assertFalse(raw.contains("Max-Age", ignoreCase = true), "session cookie must omit Max-Age, got: $raw")
    }

    @Test
    fun sessionCookieRefreshStaysSessionScoped() {
        // Rotation must preserve the session lifetime (same cookie name back), never upgrade it to persistent.
        val cookie = sessionLoginCookie()
        given().contentType(ContentType.JSON).cookie("veyq_refresh_session", cookie).body("{}")
            .`when`().post("/api/token/refresh")
            .then().statusCode(200)
            .body("refreshToken", nullValue())
            .cookie("veyq_refresh_session", notNullValue())
    }

    @Test
    fun persistentScopeSetsPersistentCookieWithMaxAge() {
        val nonce = requestChallenge(EMAIL)
        val res = verifyScope(EMAIL, sign(KEYPAIR, nonce), "PERSISTENT").then().statusCode(200)
            .body("refreshToken", nullValue())
            .cookie("veyq_refresh", notNullValue())
            .cookie("veyq_refresh_session", equalTo("")) // clears any prior session cookie (one tier per device)
            .extract()
        assertTrue(res.detailedCookie("veyq_refresh").maxAge > 0) // persistent → survives a browser restart
    }

    @Test
    fun refreshViaCookieRotatesAndOmitsBodyToken() {
        val cookie = rememberLoginCookie()
        // No body token — the refresh token rides the cookie; the rotated one comes back as a fresh cookie.
        given().contentType(ContentType.JSON).cookie("veyq_refresh", cookie).body("{}")
            .`when`().post("/api/token/refresh")
            .then().statusCode(200)
            .body("accessToken", notNullValue())
            .body("refreshToken", nullValue())
            .cookie("veyq_refresh", notNullValue())
    }

    @Test
    fun refreshWithNeitherCookieNorBodyTokenIs400() {
        given().contentType(ContentType.JSON).body("{}")
            .`when`().post("/api/token/refresh")
            .then().statusCode(400).body("code", equalTo("invalid_request"))
    }

    @Test
    fun logoutClearsRefreshCookie() {
        val session = login()
        given().header("Authorization", "Bearer ${session.accessToken}")
            .`when`().post("/api/logout")
            .then().statusCode(204)
            .cookie("veyq_refresh", equalTo("")) // Max-Age 0, empty value — the browser drops it
            .cookie("veyq_refresh_session", equalTo("")) // both lifetimes cleared (cr-0055)
    }

    // --- instance salt ---

    @Test
    fun setupStatusCarriesStableInstanceSalt() {
        val first = given().`when`().get("/api/setup").then().statusCode(200)
            .extract().path<String>("instanceSalt")
        assertTrue(first.isNotEmpty())
        Base64.getDecoder().decode(first) // valid base64
        val second = given().`when`().get("/api/setup").then().extract().path<String>("instanceSalt")
        assertEquals(first, second) // stable across calls
    }

    // --- helpers ---

    /** The status of the session family a refresh token belongs to — read straight from the store. */
    private fun familyStatusOf(refreshToken: String): String =
        QuarkusTransaction.requiringNew().call<String> {
            val row = RefreshTokenEntity.find("tokenHash", com.veyq.app.shared.crypto.Tokens.hash(refreshToken))
                .firstResult()!!
            SessionFamilyEntity.findById(row.familyId)!!.status
        }

    private data class Session(val accessToken: String, val refreshToken: String)

    private fun login(): Session {
        val nonce = requestChallenge(EMAIL)
        val res = verify(EMAIL, sign(KEYPAIR, nonce)).then().statusCode(200).extract()
        return Session(res.path("accessToken"), res.path("refreshToken"))
    }

    private fun requestChallenge(email: String): String =
        given().contentType(ContentType.JSON).body("""{"email":"$email"}""")
            .`when`().post("/api/login/challenge")
            .then().statusCode(200).extract().path("nonce")

    private fun verify(email: String, signatureB64: String) =
        given().contentType(ContentType.JSON).body("""{"email":"$email","signature":"$signatureB64"}""")
            .`when`().post("/api/login/verify")

    private fun verifyRemember(email: String, signatureB64: String) =
        given().contentType(ContentType.JSON)
            .body("""{"email":"$email","signature":"$signatureB64","remember":true}""")
            .`when`().post("/api/login/verify")

    private fun verifyScope(email: String, signatureB64: String, scope: String) =
        given().contentType(ContentType.JSON)
            .body("""{"email":"$email","signature":"$signatureB64","sessionScope":"$scope"}""")
            .`when`().post("/api/login/verify")

    private fun rememberLoginCookie(): String {
        val nonce = requestChallenge(EMAIL)
        return verifyRemember(EMAIL, sign(KEYPAIR, nonce)).then().statusCode(200)
            .extract().cookie("veyq_refresh")
    }

    private fun sessionLoginCookie(): String {
        val nonce = requestChallenge(EMAIL)
        return verifyScope(EMAIL, sign(KEYPAIR, nonce), "SESSION").then().statusCode(200)
            .extract().cookie("veyq_refresh_session")
    }

    private fun refresh(refreshToken: String) =
        given().contentType(ContentType.JSON).body("""{"refreshToken":"$refreshToken"}""")
            .`when`().post("/api/token/refresh")

    companion object {
        private const val EMAIL = "login-test@example.com"
        private val KEYPAIR: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        private val OTHER_KEYPAIR: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

        private fun rawPublicKey(kp: KeyPair): ByteArray =
            kp.public.encoded.let { it.copyOfRange(it.size - 32, it.size) }

        /** Sign the decoded nonce bytes with the keypair's private key; return standard-base64 signature. */
        private fun sign(kp: KeyPair, nonceB64: String): String {
            val sig = Signature.getInstance("Ed25519")
            sig.initSign(kp.private)
            sig.update(Base64.getDecoder().decode(nonceB64))
            return b64(sig.sign())
        }

        private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    }
}
