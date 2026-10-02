package com.veyq.app.features.account.pairing

import com.veyq.app.features.account.login.AccessTokenEntity
import com.veyq.app.features.account.login.RefreshTokenEntity
import com.veyq.app.features.account.login.SessionFamilyEntity
import com.veyq.app.shared.crypto.Tokens
import com.veyq.app.shared.identity.VeyqUser
import com.veyq.app.testing.TestFixtures.newUser
import com.veyq.app.testing.race
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Slice tests for the device-pairing channel and the session it delegates (uc.account.onboard-device,
 * ADR 0059). Drives the full HTTP stack against the running instance.
 *
 * **Every dead-channel case owes a live control on the same call.** "Unknown, expired and spent all answer 404"
 * is satisfied perfectly by an endpoint that refuses everything, so each of the three runs a *fresh* channel
 * through the same verb in the same test and asserts it still works. The negatives anchor on the specific
 * observable — the status code **and** the error code — never on "it failed".
 *
 * **A second account is live throughout**, which is what turns "the grant is scoped to the account that
 * delegated" from a claim about the SQL into an observation at the boundary.
 *
 * **Nothing here unlocks a vault, and nothing here is a client.** The server relays three opaque values; the
 * check number, the transcribed code and the sealing all happen on the devices (ADR 0059 §5, §6), so the only
 * thing these tests can — and must — assert about `answer` and `envelope` is that what came out is byte for
 * byte what went in.
 */
@QuarkusTest
class PairingSliceTest {

    @BeforeEach
    fun seedAccountsAndClearChannels() {
        QuarkusTransaction.requiringNew().run(Runnable {
            seed(USER_ID, EMAIL, KEYPAIR)
            seed(OTHER_USER_ID, OTHER_EMAIL, OTHER_KEYPAIR)
            // Channels and families outlive a test method inside one JVM (the %test H2 DB is shared), so the
            // counting assertions below would drift from test to test. Start each one from a known-empty state.
            DevicePairingEntity.delete("userId in ?1 or userId is null", listOf(USER_ID, OTHER_USER_ID))
            val families = SessionFamilyEntity.find("userId in ?1", listOf(USER_ID, OTHER_USER_ID)).list().map { it.id }
            if (families.isNotEmpty()) {
                AccessTokenEntity.delete("familyId in ?1", families)
                RefreshTokenEntity.delete("familyId in ?1", families)
                SessionFamilyEntity.delete("id in ?1", families)
            }
        })
    }

    // --- the whole ceremony, both directions ------------------------------------------------------------------

    @Test
    fun addingADeviceRelaysTheAnswerAndTheGrantAndHandsTheNewDeviceAWorkingSession() {
        val stranger = loginOther() // a live account alongside, for the whole test
        val trusted = login()
        val handle = handle()

        // 1. The trusted device opens the channel and displays the handle.
        open(handle).then().statusCode(204)

        // 2. The new device scans it and posts its ephemeral transport public key. No session, no account named.
        answer(handle, EPHEMERAL_KEY).then().statusCode(204)

        // 3. The trusted device polls and sees exactly the bytes that were posted — the server relays, it does
        //    not interpret (ADR 0059 §3). Nothing is sealed yet, and that absence is the whole state it needs.
        val answered = collect(handle).then().statusCode(200).extract()
        assertEquals(EPHEMERAL_KEY, answered.path<String>("answer"), "the answer is relayed verbatim")
        assertNull(answered.path<String?>("envelope"), "nothing has been sealed yet")

        // 4. After the user compares the check number and confirms, the trusted device mints the family.
        val refreshToken = delegate(trusted.accessToken, handle)
            .then().statusCode(200).extract().path<String>("refreshToken")

        // 5. …and only then seals, because the plaintext had to be in its hand first.
        seal(trusted.accessToken, handle, ENVELOPE).then().statusCode(204)

        // 6. The new device picks the grant up.
        val collected = collect(handle).then().statusCode(200).extract()
        assertEquals(ENVELOPE, collected.path<String>("envelope"), "the sealed grant is relayed verbatim")
        assertEquals(EPHEMERAL_KEY, collected.path<String>("answer"), "and the answer is still there")

        // 7. The delegated refresh token is a real session: it rotates into a pair of its own, which is how the
        //    granted device gets an access token it was never handed.
        val rotated = refresh(refreshToken).then().statusCode(200).extract()
        assertTrue(rotated.path<String?>("accessToken") != null, "the granted device now holds its own pair")

        // The account boundary, observed rather than argued: the delegating account gained a family, the other
        // account gained nothing at all.
        assertEquals(2, listSessions(trusted.accessToken).size, "the login family plus the delegated one")
        assertEquals(1, listSessions(stranger.accessToken).size, "the other account is untouched")
    }

    @Test
    fun aChannelOpenedWithNoSessionBecomesWhicheverAccountDelegates() {
        // The QR-sign-in direction: the channel is opened by the device that wants in, which has no session and
        // names no account — "which account this becomes is decided by whoever scans" (ADR 0059 §3). The answer
        // here is that direction's (a MAC that also binds the transcribed code), and the server cannot tell:
        // the same call, the same shape, no branch anywhere on which direction is running (§6).
        val stranger = loginOther()
        val trusted = login()
        val handle = handle()

        open(handle).then().statusCode(204) // no Authorization header anywhere in these two calls
        answer(handle, CODE_COMMITMENT).then().statusCode(204)

        delegate(trusted.accessToken, handle).then().statusCode(200)
        seal(trusted.accessToken, handle, ENVELOPE).then().statusCode(204)

        assertEquals(ENVELOPE, collect(handle).then().statusCode(200).extract().path<String>("envelope"))
        assertEquals(2, listSessions(trusted.accessToken).size, "the account that delegated owns the channel")
        assertEquals(1, listSessions(stranger.accessToken).size, "the account that did not gained nothing")
    }

    // --- E1: a dead channel is one case, and each dead case owes a live control -------------------------------

    @Test
    fun anUnknownChannelIsRefusedWhileAFreshOneAnswers() {
        val trusted = login()
        val unknown = handle()
        val live = handle().also { open(it).then().statusCode(204) }

        collect(unknown).then().statusCode(404).body("code", equalTo(INVALID))
        answer(unknown, EPHEMERAL_KEY).then().statusCode(404).body("code", equalTo(INVALID))
        delegate(trusted.accessToken, unknown).then().statusCode(404).body("code", equalTo(INVALID))
        seal(trusted.accessToken, unknown, ENVELOPE).then().statusCode(404).body("code", equalTo(INVALID))

        // The control that keeps the four lines above from being satisfied by an endpoint that refuses
        // everything: the same verbs, on a channel that is alive.
        collect(live).then().statusCode(200)
        answer(live, EPHEMERAL_KEY).then().statusCode(204)
        delegate(trusted.accessToken, live).then().statusCode(200)
        seal(trusted.accessToken, live, ENVELOPE).then().statusCode(204)
    }

    @Test
    fun anExpiredChannelIsRefusedWhileAFreshOneAnswers() {
        val trusted = login()
        val stale = handle().also { open(it).then().statusCode(204) }
        answer(stale, EPHEMERAL_KEY).then().statusCode(204)
        expire(stale)
        val live = handle().also { open(it).then().statusCode(204) }

        collect(stale).then().statusCode(404).body("code", equalTo(INVALID))
        delegate(trusted.accessToken, stale).then().statusCode(404).body("code", equalTo(INVALID))

        collect(live).then().statusCode(200)
        answer(live, EPHEMERAL_KEY).then().statusCode(204)
        delegate(trusted.accessToken, live).then().statusCode(200)
    }

    @Test
    fun aSpentChannelIsRefusedWhileAFreshOneAnswers() {
        val trusted = login()
        val spent = fullyPairedChannel(trusted)
        collect(spent).then().statusCode(200) // the collect that spends it
        val live = handle().also { open(it).then().statusCode(204) }

        collect(spent).then().statusCode(404).body("code", equalTo(INVALID))
        seal(trusted.accessToken, spent, ENVELOPE).then().statusCode(404).body("code", equalTo(INVALID))

        collect(live).then().statusCode(200)
        answer(live, EPHEMERAL_KEY).then().statusCode(204)
    }

    @Test
    fun readingTheAnswerSpendsNothingButTakingTheGrantSpendsTheChannel() {
        val trusted = login()
        val handle = handle()
        open(handle).then().statusCode(204)
        answer(handle, EPHEMERAL_KEY).then().statusCode(204)

        // The trusted device polls; a poll must not consume the ceremony it is waiting on.
        repeat(3) { collect(handle).then().statusCode(200).body("answer", equalTo(EPHEMERAL_KEY)) }

        delegate(trusted.accessToken, handle).then().statusCode(200)
        seal(trusted.accessToken, handle, ENVELOPE).then().statusCode(204)

        collect(handle).then().statusCode(200).body("envelope", equalTo(ENVELOPE))
        collect(handle).then().statusCode(404).body("code", equalTo(INVALID)) // single-use (§8)
    }

    @Test
    fun concurrentCollectsHandOverTheGrantOnce() {
        // Two collects race one sealed channel. Spending it must be ATOMIC: exactly one gets the envelope, the
        // other answers as a spent channel does. Without the compare-and-set both read SEALED before either flip
        // lands, and the joining device runs its onboarding tail twice (ADR 0059 §8).
        val trusted = login()
        val raced = fullyPairedChannel(trusted)
        val barrier = java.util.concurrent.CyclicBarrier(2)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        val outcomes = try {
            pool.invokeAll(
                (0 until 2).map {
                    java.util.concurrent.Callable {
                        barrier.await() // release both collects as tightly together as possible
                        collect(raced).then().extract().let { it.statusCode() to it.body().asString() }
                    }
                },
            ).map { it.get() }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, outcomes.count { it.first == 200 && it.second.contains(ENVELOPE) },
            "exactly one concurrent collect may take the grant, got $outcomes")
        assertEquals(1, outcomes.count { it.first == 404 && it.second.contains("\"$INVALID\"") },
            "the losing collect must answer as a spent channel does, got $outcomes")

        // The control: a fresh channel's single collect still hands the grant over, so the refusal above is about
        // the race and not about sealed channels being uncollectable. Opened after the race on purpose — a second
        // delegation supersedes the account's other live channels (§8).
        val control = fullyPairedChannel(trusted)
        collect(control).then().statusCode(200).body("envelope", equalTo(ENVELOPE))
    }

    @Test
    fun theThreeDeadChannelsAreIndistinguishableFromOneAnother() {
        val trusted = login()
        val unknown = handle()
        val stale = handle().also { open(it).then().statusCode(204) }.also { expire(it) }
        val spent = fullyPairedChannel(trusted).also { collect(it).then().statusCode(200) }

        val answers = listOf(unknown, stale, spent).map { collect(it) }
        answers.forEach { it.then().statusCode(404) }
        assertEquals(
            1, answers.map { it.statusCode to it.body.asString() }.toSet().size,
            "unknown, expired and spent must be one answer, byte for byte — otherwise the endpoint says which " +
                "pairings exist (uc.account.onboard-device E1)",
        )
    }

    // --- the account boundary and the one-delegation rule -----------------------------------------------------

    @Test
    fun anotherAccountCannotSealToAChannelItDidNotDelegate() {
        val trusted = login()
        val stranger = loginOther()
        val handle = handle()
        open(handle).then().statusCode(204)
        answer(handle, EPHEMERAL_KEY).then().statusCode(204)
        delegate(trusted.accessToken, handle).then().statusCode(200)

        // Same answer a dead channel gets: whose channel this is must not be readable from the refusal.
        seal(stranger.accessToken, handle, "c3RyYW5nZXItZW52ZWxvcGU=").then().statusCode(404).body("code", equalTo(INVALID))
        assertNull(
            collect(handle).then().statusCode(200).extract().path<String?>("envelope"),
            "and nothing of the stranger's was written",
        )

        // The control: the account that did delegate seals fine, so the refusal above is about the account and
        // not about the channel being unsealable.
        seal(trusted.accessToken, handle, ENVELOPE).then().statusCode(204)
        assertEquals(ENVELOPE, collect(handle).then().statusCode(200).extract().path<String>("envelope"))
    }

    @Test
    fun aChannelIsDelegatedOnceAndTheSecondAttemptMintsNothing() {
        val trusted = login()
        val handle = handle()
        open(handle).then().statusCode(204)
        answer(handle, EPHEMERAL_KEY).then().statusCode(204)

        val first = delegate(trusted.accessToken, handle).then().statusCode(200).extract().path<String>("refreshToken")
        delegate(trusted.accessToken, handle).then().statusCode(404).body("code", equalTo(INVALID))

        // A second family would be one no channel records and no sweep could ever revoke — so: still two, and
        // the token from the first delegation is the one that works.
        assertEquals(2, listSessions(trusted.accessToken).size, "the login family plus exactly one delegated")
        refresh(first).then().statusCode(200)
    }

    @Test
    fun concurrentDelegationsClaimTheChannelOnceAndMintOneFamily() {
        // The same rule raced: two delegations that both read the channel unclaimed. Before the claim was a
        // compare-and-set both answered 200 with different refresh tokens, and the overwritten family was a live
        // 30-day token no channel recorded and no sweep could find. The family count is what pins the ORDER —
        // claim, then mint: a loser that minted first would orphan a family even though it answered 404.
        repeat(ROUNDS) { round ->
            seedAccountsAndClearChannels()
            val trusted = login()
            val handle = handle()
            open(handle).then().statusCode(204)
            answer(handle, EPHEMERAL_KEY).then().statusCode(204)
            val answers = race({ delegateAnswer(trusted.accessToken, handle) }, { delegateAnswer(trusted.accessToken, handle) })
            assertEquals(1, answers.count { it.first == 200 }, "round $round: exactly one delegation may win, got $answers")
            assertEquals(1, answers.count { it == (404 to INVALID) }, "round $round: the loser answers as a spent channel, got $answers")
            assertEquals(2L, activeFamilies(), "round $round: the login family plus exactly one delegated, got $answers")
            refresh(answers.single { it.first == 200 }.second!!).then().statusCode(200)
        }
        // Control: a fresh channel delegated once still answers with a working token.
        seedAccountsAndClearChannels()
        val trusted = login()
        val handle = handle()
        open(handle).then().statusCode(204)
        answer(handle, EPHEMERAL_KEY).then().statusCode(204)
        refresh(delegate(trusted.accessToken, handle).then().statusCode(200).extract().path("refreshToken")).then().statusCode(200)
    }

    @Test
    fun delegatingSupersedesTheAccountsOtherPairingAndRevokesTheFamilyItLeftBehind() {
        val stranger = loginOther()
        val trusted = login()

        // A first ceremony, confirmed and then walked away from: it holds a live 30-day refresh token nobody
        // will ever collect. That is what §8's one-outstanding-pairing rule is for.
        val abandoned = handle()
        open(abandoned).then().statusCode(204)
        answer(abandoned, EPHEMERAL_KEY).then().statusCode(204)
        val orphaned = delegate(trusted.accessToken, abandoned).then().statusCode(200).extract().path<String>("refreshToken")

        // A stranger's ceremony, live across the whole test — the account boundary holds for superseding too.
        val strangersChannel = handle()
        open(strangersChannel).then().statusCode(204)
        answer(strangersChannel, EPHEMERAL_KEY).then().statusCode(204)
        val strangersToken = delegate(stranger.accessToken, strangersChannel).then().statusCode(200).extract().path<String>("refreshToken")

        // The person starts over.
        val fresh = handle()
        open(fresh).then().statusCode(204)
        answer(fresh, EPHEMERAL_KEY).then().statusCode(204)
        val current = delegate(trusted.accessToken, fresh).then().statusCode(200).extract().path<String>("refreshToken")

        // The abandoned channel is gone, and — the half a status code cannot show — so is the session it minted.
        collect(abandoned).then().statusCode(404).body("code", equalTo(INVALID))
        refresh(orphaned).then().statusCode(401).body("code", equalTo("refresh_rejected"))

        // Two live controls, without which "the old one died" is satisfied by killing everything: the new
        // ceremony works, and the other account's pairing is untouched.
        refresh(current).then().statusCode(200)
        seal(trusted.accessToken, fresh, ENVELOPE).then().statusCode(204)
        collect(strangersChannel).then().statusCode(200)
        refresh(strangersToken).then().statusCode(200)
    }

    // --- postures --------------------------------------------------------------------------------------------

    @Test
    fun theRelayIsPublicAndTheGrantingHalfIsNot() {
        // Three endpoints must work with no session at all: in one direction or the other the caller is the
        // device being onboarded, which has none (ADR 0059 §3). The other two must refuse — and this pairing is
        // the live control for both halves, because "public" is satisfied by an open server and "authenticated"
        // by a closed one.
        val handle = handle()
        given().contentType(ContentType.JSON).body("""{"handle":"$handle"}""")
            .`when`().post("/api/pairing/open").then().statusCode(204)
        given().contentType(ContentType.JSON).body("""{"handle":"$handle","answer":"$EPHEMERAL_KEY"}""")
            .`when`().post("/api/pairing/answer").then().statusCode(204)
        given().contentType(ContentType.JSON).body("""{"handle":"$handle"}""")
            .`when`().post("/api/pairing/collect").then().statusCode(200)

        given().contentType(ContentType.JSON).body("""{"handle":"$handle"}""")
            .`when`().post("/api/pairing/delegate").then().statusCode(401)
        given().contentType(ContentType.JSON).body("""{"handle":"$handle","envelope":"$ENVELOPE"}""")
            .`when`().post("/api/pairing/seal").then().statusCode(401)
    }

    @Test
    fun nothingOnThisWireCarriesAnythingButTheHandleAndTheTwoOpaqueValues() {
        // The operator-blind criterion is structural, so the honest assertion is over the response SHAPE: the
        // read hands back exactly two fields, and a status, an account or — the one ADR 0059 §4 names — a server
        // address has nowhere to arrive. This is the line that fails the day someone adds one, and
        // scripts/validate_pairing_shapes.py is its contract-side twin.
        val trusted = login()
        val handle = fullyPairedChannel(trusted)

        // Both fields are set on a sealed channel, so the map is total — an earlier state would say nothing
        // about the shape, only about how far the ceremony had got.
        val body = collect(handle).then().statusCode(200).extract().jsonPath().getMap<String, Any>("$")

        assertEquals(setOf("answer", "envelope"), body.keys, "the channel read exposes exactly these two fields")
        assertFalse(
            body.values.any { it.toString().contains("http", ignoreCase = true) },
            "no value on this wire may be URL-shaped (ADR 0059 §4)",
        )
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    private data class Session(val familyId: String, val accessToken: String, val refreshToken: String)

    /** A channel taken all the way to SEALED, ready for the one collect that spends it. */
    private fun fullyPairedChannel(trusted: Session): String {
        val handle = handle()
        open(handle).then().statusCode(204)
        answer(handle, EPHEMERAL_KEY).then().statusCode(204)
        delegate(trusted.accessToken, handle).then().statusCode(200)
        seal(trusted.accessToken, handle, ENVELOPE).then().statusCode(204)
        return handle
    }

    /** A fresh routing handle. **Standard** base64, not base64url: the contract pins
     *  `[A-Za-z0-9+/]*={0,2}`, so a `-` or `_` from a url-safe encoder is a 400 at the boundary. */
    private fun handle(): String {
        val bytes = ByteArray(32).also { RNG.nextBytes(it) }
        return Base64.getEncoder().encodeToString(bytes)
    }

    /** Push a channel past its TTL. The endpoint checks liveness at read (`PairingStore.isLive`), so this is
     *  the same state the sweep would eventually write, reached without waiting for it. */
    private fun expire(handle: String) {
        QuarkusTransaction.requiringNew().run(Runnable {
            DevicePairingEntity.update(
                "expiresAt = ?1 where handleHash = ?2",
                Instant.now().minusSeconds(60), Tokens.hash(handle),
            )
        })
    }

    private fun open(handle: String): Response = given().contentType(ContentType.JSON)
        .body("""{"handle":"$handle"}""")
        .`when`().post("/api/pairing/open")

    private fun answer(handle: String, answer: String): Response = given().contentType(ContentType.JSON)
        .body("""{"handle":"$handle","answer":"$answer"}""")
        .`when`().post("/api/pairing/answer")

    private fun collect(handle: String): Response = given().contentType(ContentType.JSON)
        .body("""{"handle":"$handle"}""")
        .`when`().post("/api/pairing/collect")

    private fun delegate(accessToken: String, handle: String): Response = given()
        .header("Authorization", "Bearer $accessToken")
        .contentType(ContentType.JSON)
        .body("""{"handle":"$handle"}""")
        .`when`().post("/api/pairing/delegate")

    /** A delegation as (status, refresh token on success / error code on failure). */
    private fun delegateAnswer(accessToken: String, handle: String): Pair<Int, String?> =
        delegate(accessToken, handle).then().extract().let {
            it.statusCode() to (if (it.statusCode() == 200) it.path<String>("refreshToken") else it.path<String?>("code"))
        }

    /** The test account's session families that can still refresh — a delegated family counts from its mint. */
    private fun activeFamilies(): Long = QuarkusTransaction.requiringNew().call<Long> {
        SessionFamilyEntity.count("userId = ?1 and status = ?2", USER_ID, SessionFamilyEntity.ACTIVE)
    }

    private fun seal(accessToken: String, handle: String, envelope: String): Response = given()
        .header("Authorization", "Bearer $accessToken")
        .contentType(ContentType.JSON)
        .body("""{"handle":"$handle","envelope":"$envelope"}""")
        .`when`().post("/api/pairing/seal")

    private fun refresh(refreshToken: String): Response = given().contentType(ContentType.JSON)
        .body("""{"refreshToken":"$refreshToken"}""")
        .`when`().post("/api/token/refresh")

    private fun listSessions(accessToken: String): List<Map<String, Any?>> =
        given().header("Authorization", "Bearer $accessToken")
            .`when`().get("/api/sessions")
            .then().statusCode(200).extract().jsonPath().getList("$", Map::class.java)
            .map { @Suppress("UNCHECKED_CAST") (it as Map<String, Any?>) }

    private fun login(): Session = loginAs(EMAIL, KEYPAIR)

    private fun loginOther(): Session = loginAs(OTHER_EMAIL, OTHER_KEYPAIR)

    private fun loginAs(email: String, keyPair: KeyPair): Session {
        val nonce = given().contentType(ContentType.JSON).body("""{"email":"$email"}""")
            .`when`().post("/api/login/challenge")
            .then().statusCode(200).extract().path<String>("nonce")
        val res = given().contentType(ContentType.JSON)
            .body("""{"email":"$email","nonce":"$nonce","signature":"${sign(keyPair, nonce)}"}""")
            .`when`().post("/api/login/verify")
            .then().statusCode(200).extract()
        return Session(res.path("family.id"), res.path("accessToken"), res.path("refreshToken"))
    }

    companion object {
        private const val EMAIL = "pairing-test@example.com"
        private const val OTHER_EMAIL = "pairing-stranger@example.com"
        private val USER_ID = UUID.nameUUIDFromBytes("pairing-test-user".toByteArray()).toString()
        private val OTHER_USER_ID = UUID.nameUUIDFromBytes("pairing-stranger-user".toByteArray()).toString()

        /** The one answer every dead channel gets, whichever way it died (E1). */
        private const val INVALID = "pairing_channel_invalid"

        /** The answering device's ephemeral transport public key — opaque to the server (ADR 0059 §3). */
        private const val EPHEMERAL_KEY = "ZXBoZW1lcmFsLXRyYW5zcG9ydC1wdWJsaWMta2V5"

        /** The other direction's answer: key and a MAC that binds the transcribed code (§6, §11). Same field, same
         *  shape — the server must not be able to tell the two apart. */
        private const val CODE_COMMITMENT = "Y29tbWl0bWVudC10by10aGUtdHlwZWQtY29kZQ=="

        /** The sealed grant: Master Key + delegated refresh token + label, wrapped to the answering key. */
        private const val ENVELOPE = "c2VhbGVkLWdyYW50LWNpcGhlcnRleHQ="

        /** One round of a race can pass by luck; each round races a fresh channel. */
        private const val ROUNDS = 5

        private val RNG = SecureRandom()
        private val KEYPAIR: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        private val OTHER_KEYPAIR: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

        private fun seed(userId: String, email: String, keyPair: KeyPair) {
            if (VeyqUser.find("email", email).firstResult() != null) return
            newUser(
                id = userId, email = email,
                authPublicKey = b64(keyPair.public.encoded.let { it.copyOfRange(it.size - 32, it.size) }),
            ).persist()
        }

        private fun sign(kp: KeyPair, nonceB64: String): String {
            val sig = Signature.getInstance("Ed25519")
            sig.initSign(kp.private)
            sig.update(Base64.getDecoder().decode(nonceB64))
            return b64(sig.sign())
        }

        private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    }
}
