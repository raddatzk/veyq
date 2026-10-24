package me.raddatz.veyq.core.account.login

import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.testing.TestFixtures.newUser
import me.raddatz.veyq.core.testing.race
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
import me.raddatz.veyq.core.testing.raceRounds
import me.raddatz.veyq.core.testing.holdForOverlap

/**
 * Slice tests for challenge-response login + sessions (uc.account.login). Seeds a user with a REAL
 * Ed25519 auth keypair directly (the setup endpoint stores a dummy key and is single-owner-locked), then
 * drives challenge → sign → verify and the token lifecycle through the running instance.
 */
@QuarkusTest
class LoginSliceTest {

    @BeforeEach
    fun seedAccount() {
        QuarkusTransaction.requiringNew().run(Runnable {
            if (VeyqUser.find("email", EMAIL).firstResult() == null) {
                newUser(
                    id = "4a00c728-6a78-436c-9b6c-223064433d36", email = EMAIL, serverRole = "SERVER_OWNER",
                    authPublicKey = b64(rawPublicKey(KEYPAIR)),
                ).persist()
            }
        })
    }

    // --- login (challenge → verify) ---

    @Test
    fun validLoginIssuesSession() {
        val nonce = requestChallenge(EMAIL)
        verify(EMAIL, nonce, sign(KEYPAIR, nonce))
            .then().statusCode(200)
            .body("accessToken", notNullValue())
            .body("refreshToken", notNullValue())
            .body("family.id", notNullValue())
    }

    @Test
    fun wrongSignatureIsGenericFailure() {
        val nonce = requestChallenge(EMAIL)
        verify(EMAIL, nonce, sign(OTHER_KEYPAIR, nonce)) // signed by the wrong key
            .then().statusCode(401).body("code", equalTo("authentication_failed"))
    }

    @Test
    fun unknownAccountIsIdenticalGenericFailure() {
        // Challenge for a non-existent account still returns 200 with a nonce (non-enumerable)...
        val nonce = requestChallenge("ghost@example.com")
        assertTrue(nonce.isNotEmpty())
        // ...and verify fails with the SAME generic code as a wrong signature.
        verify("ghost@example.com", nonce, sign(KEYPAIR, nonce))
            .then().statusCode(401).body("code", equalTo("authentication_failed"))
    }

    @Test
    fun replayedNonceIsRejected() {
        val nonce = requestChallenge(EMAIL)
        val sig = sign(KEYPAIR, nonce)
        verify(EMAIL, nonce, sig).then().statusCode(200) // single use consumes the challenge
        verify(EMAIL, nonce, sig).then().statusCode(401) // replay → no outstanding nonce
    }

    @Test
    fun nobodyElseCanDisplaceOrBurnYourChallenge() {
        // uc.account.login E4. The victim's device holds challenge A and is still running Argon2id. Someone who
        // only knows the address asks for a challenge of their own (B), burns it with a garbage verify, sends one
        // naming a nonce that was never issued, and one naming A under another email. None of it may touch A.
        val a = requestChallenge(EMAIL)
        val b = requestChallenge(EMAIL)
        val garbage = b64(ByteArray(64))
        verify(EMAIL, b, garbage).then().statusCode(401)
        verify(EMAIL, b64(ByteArray(32) { 7 }), garbage).then().statusCode(401)
        verify("ghost@example.com", a, garbage).then().statusCode(401)
        verify(EMAIL, a, sign(KEYPAIR, a)).then().statusCode(200)
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
                        verify(EMAIL, nonce, sig).then().extract().statusCode()
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
            val challenge = LoginChallengeEntity.find("nonce", nonce).firstResult()!!
            challenge.expiresAt = Instant.now().minus(1, ChronoUnit.MINUTES)
            challenge.persist()
        })
        verify(EMAIL, nonce, sign(KEYPAIR, nonce)) // valid signature, expired challenge
            .then().statusCode(401).body("code", equalTo("authentication_failed"))
    }

    // --- boundary validation (uc.account.login/boundary-validation) ---

    @Test
    fun malformedRequestsRejectedAtTheBoundary() {
        // The spec's x-field-extra-annotation makes @Valid reject malformed input with 400 BEFORE the handler;
        // these are shape violations, answered as the contract's `400 invalid_request` by core's boundary mappers
        // (inbox #355) — the same body a handler's refusal carries, and nothing of the refused email or key.
        // Missing email on the challenge → @NotBlank.
        given().contentType(ContentType.JSON).body("{}")
            .`when`().post("/api/login/challenge")
            .then().statusCode(400).body("code", equalTo("invalid_request"))
        // Malformed email on the challenge → @Email.
        given().contentType(ContentType.JSON).body("""{"email":"not-an-email"}""")
            .`when`().post("/api/login/challenge")
            .then().statusCode(400).body("code", equalTo("invalid_request"))
        // Non-base64 signature on verify → @Pattern, rejected before any nonce lookup.
        given().contentType(ContentType.JSON).body("""{"email":"$EMAIL","nonce":"AA==","signature":"not base64!!"}""")
            .`when`().post("/api/login/verify")
            .then().statusCode(400).body("code", equalTo("invalid_request"))
        // A verify that names no nonce → @NotBlank. There is no "take the newest challenge" fallback: that
        // would keep a challenge displaceable for every client that omits the field (uc.account.login E4).
        given().contentType(ContentType.JSON).body("""{"email":"$EMAIL","signature":"AA=="}""")
            .`when`().post("/api/login/verify")
            .then().statusCode(400).body("code", equalTo("invalid_request"))
        // Non-base64 nonce on verify → @Pattern.
        given().contentType(ContentType.JSON).body("""{"email":"$EMAIL","nonce":"not base64!!","signature":"AA=="}""")
            .`when`().post("/api/login/verify")
            .then().statusCode(400).body("code", equalTo("invalid_request"))
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
    fun twoRefreshesOfOneTokenQueuedOnTheFamilyBothRotateAndKeepIt() {
        // The deterministic form of the race above (inbox #302): a test transaction holds the family row while both
        // refreshes read the token's family id and queue on that lock, so the loser always decides AFTER the winner
        // committed. Its grace must then measure distance one against the winner's successor. Reading the CURRENT
        // row from a snapshot taken before the lock wait (MariaDB at REPEATABLE READ, with a plain read) finds the
        // presented row itself, measures distance zero and revokes the family — 800 of 800 races before ADR 0078.
        raceRounds(FORK_ROUNDS) { round ->
            val r1 = login().refreshToken
            val familyId = familyIdOf(r1)
            val answers = whileFamilyIsHeld(familyId) { race({ refreshAnswer(r1) }, { refreshAnswer(r1) }) }
            assertEquals(listOf(200, 200), answers.map { it.first }, "round $round: both presentations rotate, got $answers")
            assertEquals("ACTIVE", familyStatusOf(r1), "round $round: a same-token race must not revoke, got $answers")
            assertEquals(1L, currentRows(familyId), "round $round: one family, one CURRENT row, got $answers")
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
    fun theOrphanAndTheCurrentTokenPresentedTogetherCannotForkTheChain() {
        // The grace's distance is measured against the family's CURRENT row. An orphan (distance 1) and the current
        // token lock DIFFERENT token rows, so without a lock on their common parent both measured a CURRENT that the
        // other was replacing: both answered 200, the family held two live chains, and neither holder ever landed
        // on distance zero — ADR 0004's detector silenced. Serial, either order revokes (distance 0 or 2).
        // A test transaction holds the family row while both start, so they queue on it together; 10 rounds, because
        // that hold made the fork 3 of 5 in the probe, not 5 of 5.
        raceRounds(FORK_ROUNDS) { round ->
            val r1 = login().refreshToken
            val r2 = refresh(r1).then().statusCode(200).extract().path<String>("refreshToken") // r1 is now one behind
            val familyId = familyIdOf(r1)
            val answers = whileFamilyIsHeld(familyId) { race({ refreshAnswer(r1) }, { refreshAnswer(r2) }) }
            // Only 200 or 401: a 500 is the deadlock a reversed lock order (token row, then family) produces here, and
            // nothing retries it any more (inbox #302) — so this line is what holds the lock order.
            assertTrue(answers.all { it.first == 200 || it.first == 401 }, "round $round: a refresh failed, got $answers")
            assertTrue(answers.count { it.first == 200 } <= 1, "round $round: at most one presentation may rotate, got $answers")
            assertTrue(currentRows(familyId) <= 1, "round $round: one family, one CURRENT row, got answers $answers")
            val again = answers.mapNotNull { it.second }.map { refresh(it).then().extract().statusCode() }
            assertTrue(again.count { it == 200 } <= 1, "round $round: no two chains may keep rotating, got $again")
        }
        // Control: an ordinary refresh still rotates, so a lock that refused everything cannot pass.
        refresh(login().refreshToken).then().statusCode(200)
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
        val res = verifyRemember(EMAIL, nonce, sign(KEYPAIR, nonce)).then().statusCode(200)
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
        val res = verifyScope(EMAIL, nonce, sign(KEYPAIR, nonce), "SESSION").then().statusCode(200)
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
        val res = verifyScope(EMAIL, nonce, sign(KEYPAIR, nonce), "PERSISTENT").then().statusCode(200)
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
    fun refreshWithNoCredentialIs401NoSessionAndARejectedTokenIs401RefreshRejected() {
        // Neither a cookie nor a body token — every signed-out web boot — holds no credential: a 401 (RFC 9110), not a
        // malformed request, under its own code (inbox #535).
        given().contentType(ContentType.JSON).body("{}")
            .`when`().post("/api/token/refresh")
            .then().statusCode(401).body("code", equalTo("no_session"))
        // A token that arrived and is refused keeps its own code, so a log tells the two apart — by cookie and by body.
        given().contentType(ContentType.JSON).cookie("veyq_refresh", "not-a-real-token").body("{}")
            .`when`().post("/api/token/refresh")
            .then().statusCode(401).body("code", equalTo("refresh_rejected"))
        refresh("not-a-real-token").then().statusCode(401).body("code", equalTo("refresh_rejected"))
    }

    @Test
    fun aMalformedRefreshRequestIsStill400() {
        // The 400 stays the shape's: a token past its 512-character bound is refused before the handler.
        refresh("x".repeat(513)).then().statusCode(400).body("code", equalTo("invalid_request"))
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
            val row = RefreshTokenEntity.find("tokenHash", me.raddatz.veyq.core.crypto.Tokens.hash(refreshToken))
                .firstResult()!!
            SessionFamilyEntity.findById(row.familyId)!!.status
        }

    private fun familyIdOf(refreshToken: String): String =
        QuarkusTransaction.requiringNew().call<String> {
            RefreshTokenEntity.find("tokenHash", me.raddatz.veyq.core.crypto.Tokens.hash(refreshToken)).firstResult()!!.familyId
        }

    private fun currentRows(familyId: String): Long =
        QuarkusTransaction.requiringNew().call<Long> {
            RefreshTokenEntity.count("familyId = ?1 and status = ?2", familyId, RefreshTokenEntity.CURRENT)
        }

    /** A refresh as (status, the new refresh token on success). */
    private fun refreshAnswer(refreshToken: String): Pair<Int, String?> =
        refresh(refreshToken).then().extract().let {
            it.statusCode() to (if (it.statusCode() == 200) it.path<String>("refreshToken") else null)
        }

    /**
     * Run [body] while a test transaction holds the family row with `PESSIMISTIC_WRITE` ([holdForOverlap]), so every
     * refresh it starts queues on that row together. The hold lives in the test's own transaction; nothing in
     * production code waits for a test.
     */
    private fun <T> whileFamilyIsHeld(familyId: String, body: () -> T): T {
        val held = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val holder = pool.submit(
                java.util.concurrent.Callable {
                    QuarkusTransaction.requiringNew().call {
                        SessionFamilyEntity.findById(familyId, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
                        held.countDown()
                        holdForOverlap()
                    }
                },
            )
            held.await()
            val result = body()
            holder.get().requireForced() // no refresh queued on the held row: the round raced nothing (inbox #512)
            return result
        } finally {
            pool.shutdownNow()
        }
    }

    private data class Session(val accessToken: String, val refreshToken: String)

    private fun login(): Session {
        val nonce = requestChallenge(EMAIL)
        val res = verify(EMAIL, nonce, sign(KEYPAIR, nonce)).then().statusCode(200).extract()
        return Session(res.path("accessToken"), res.path("refreshToken"))
    }

    private fun requestChallenge(email: String): String =
        given().contentType(ContentType.JSON).body("""{"email":"$email"}""")
            .`when`().post("/api/login/challenge")
            .then().statusCode(200).extract().path("nonce")

    private fun verify(email: String, nonce: String, signatureB64: String) =
        given().contentType(ContentType.JSON)
            .body("""{"email":"$email","nonce":"$nonce","signature":"$signatureB64"}""")
            .`when`().post("/api/login/verify")

    private fun verifyRemember(email: String, nonce: String, signatureB64: String) =
        given().contentType(ContentType.JSON)
            .body("""{"email":"$email","nonce":"$nonce","signature":"$signatureB64","remember":true}""")
            .`when`().post("/api/login/verify")

    private fun verifyScope(email: String, nonce: String, signatureB64: String, scope: String) =
        given().contentType(ContentType.JSON)
            .body("""{"email":"$email","nonce":"$nonce","signature":"$signatureB64","sessionScope":"$scope"}""")
            .`when`().post("/api/login/verify")

    private fun rememberLoginCookie(): String {
        val nonce = requestChallenge(EMAIL)
        return verifyRemember(EMAIL, nonce, sign(KEYPAIR, nonce)).then().statusCode(200)
            .extract().cookie("veyq_refresh")
    }

    private fun sessionLoginCookie(): String {
        val nonce = requestChallenge(EMAIL)
        return verifyScope(EMAIL, nonce, sign(KEYPAIR, nonce), "SESSION").then().statusCode(200)
            .extract().cookie("veyq_refresh_session")
    }

    private fun refresh(refreshToken: String) =
        given().contentType(ContentType.JSON).body("""{"refreshToken":"$refreshToken"}""")
            .`when`().post("/api/token/refresh")

    companion object {
        private const val EMAIL = "login-test@example.com"

        /** The fork needs both refreshes queued on one held row; the probe forked 3 of 5 rounds that way. */
        private const val FORK_ROUNDS = 10
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
