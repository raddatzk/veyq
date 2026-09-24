package com.veyq.app.features.account.login

import com.veyq.app.shared.identity.VeyqUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

/**
 * Slice tests for the session manager (uc.account.sessions): list every active session, end one remotely, and
 * sign out everywhere else. Drives the full HTTP stack against the running instance.
 *
 * **Every negative here owes a live control, because each is trivially satisfied by an over-broad
 * implementation.** A revoke that kills the whole account passes "the target can no longer refresh" perfectly;
 * a list that returns nothing passes "another account's family is not listed" perfectly. So each revoke asserts
 * what *survives* alongside what died, and the negatives anchor on the specific observable — the refused
 * refresh **by its status code** — never on "it failed".
 *
 * **Nothing in this suite unlocks a vault.** That is the point, not an omission: a session is auth, not unlock
 * (ADR 0003), so a locked device must be able to cut off a lost one. Every request below carries an access
 * token and nothing else.
 *
 * A second account runs alongside the first throughout — it is what turns "scoped to the caller" from a claim
 * about the SQL into an observation at the boundary.
 */
@QuarkusTest
class SessionManagerSliceTest {

    @Inject
    lateinit var sessions: SessionService

    @BeforeEach
    fun seedAccountsAndClearSessions() {
        QuarkusTransaction.requiringNew().run(Runnable {
            seed(USER_ID, EMAIL, KEYPAIR)
            seed(OTHER_USER_ID, OTHER_EMAIL, OTHER_KEYPAIR)
            // Families outlive a test method inside one JVM (the %test H2 DB is shared), so the counting
            // assertions below would drift from test to test. Start each one from a known-empty session state.
            val families = SessionFamilyEntity.find("userId in ?1", listOf(USER_ID, OTHER_USER_ID)).list().map { it.id }
            if (families.isNotEmpty()) {
                AccessTokenEntity.delete("familyId in ?1", families)
                RefreshTokenEntity.delete("familyId in ?1", families)
                SessionFamilyEntity.delete("id in ?1", families)
            }
        })
    }

    // --- list-shows-every-active-family ---------------------------------------------------------------------

    @Test
    fun listShowsEveryActiveFamilyOfTheCallerAndMarksExactlyOneAsCurrent() {
        val first = login()
        val second = login()
        val stranger = loginOther() // a live family on ANOTHER account, for the whole test

        val families = listSessions(first.accessToken)

        assertEquals(
            setOf(first.familyId, second.familyId),
            families.map { it["id"] }.toSet(),
            "the list must be exactly the caller's own active families",
        )
        assertTrue(
            families.none { it["id"] == stranger.familyId },
            "another account's family must never appear in this list",
        )
        // The current-marker is the positive control for sign-out-everywhere-else: a response that marks
        // nothing would satisfy "the caller stayed signed in" for entirely the wrong reason.
        assertEquals(
            listOf(first.familyId),
            families.filter { it["current"] == true }.map { it["id"] },
            "exactly one family — the one the request arrived on — is marked current",
        )
        families.forEach {
            assertTrue(it["createdAt"] != null && it["lastUsedAt"] != null, "each family carries both timestamps")
        }
    }

    @Test
    fun revokedFamiliesLeaveTheList() {
        val keep = login()
        val doomed = login()

        revoke(keep.accessToken, doomed.familyId).then().statusCode(204)

        val families = listSessions(keep.accessToken)
        assertEquals(listOf(keep.familyId), families.map { it["id"] }, "a revoked family is no longer listed")
    }

    @Test
    fun anExpiredFamilyLeavesTheListBeforeAnyPurgeRemovesIt() {
        val caller = login()
        // The live control shares the predicate: it too carries an expired refresh token — the generation its
        // rotation superseded — and differs only in that its CURRENT one is still live. A filter keyed on "has an
        // expired token" rather than "can still refresh" drops this family, and the list assertion says so.
        val live = login()
        refresh(live.refreshToken).then().statusCode(200)
        val lapsed = login()

        QuarkusTransaction.requiringNew().run(Runnable {
            val past = Instant.now().minus(1, ChronoUnit.MINUTES)
            RefreshTokenEntity.update(
                "expiresAt = ?1 where familyId = ?2 and status = ?3",
                past, live.familyId, RefreshTokenEntity.SUPERSEDED,
            )
            RefreshTokenEntity.update("expiresAt = ?1 where familyId = ?2", past, lapsed.familyId)
            AccessTokenEntity.update("expiresAt = ?1 where familyId = ?2", past, lapsed.familyId)
        })

        // The lapsed family is ended — refresh expiry means a full re-login (ADR 0004)...
        refresh(lapsed.refreshToken).then().statusCode(401).body("code", equalTo("refresh_rejected"))
        // ...and yet it is still an ACTIVE row: a family has no expired status, and only the next purge deletes
        // it. So what the list below proves is the read-time filter, not the purge having got there first.
        assertEquals(
            SessionFamilyEntity.ACTIVE,
            QuarkusTransaction.requiringNew().call { SessionFamilyEntity.findById(lapsed.familyId)?.status },
            "the lapsed family must still be an unpurged ACTIVE row, or this test proves nothing about the list",
        )

        assertEquals(
            setOf(caller.familyId, live.familyId),
            listSessions(caller.accessToken).map { it["id"] }.toSet(),
            "a family that can no longer refresh is not an active session, whatever its status column says",
        )
    }

    // --- revoke-one-family-remotely -------------------------------------------------------------------------

    @Test
    fun revokingAnotherFamilyEndsItAndLeavesTheCallerSignedIn() {
        val caller = login()
        val target = login()

        revoke(caller.accessToken, target.familyId).then().statusCode(204)

        // The negative, on its specific observable: the revoked family's refresh is refused by status code and
        // by the error code the refresh endpoint uses for a dead family — not merely "it didn't work".
        refresh(target.refreshToken).then().statusCode(401).body("code", equalTo("refresh_rejected"))
        // The live control that makes the line above mean something: revoking one session is not revoking all.
        refresh(caller.refreshToken).then().statusCode(200)
    }

    @Test
    fun revokingIsIdempotent() {
        val caller = login()
        val target = login()

        revoke(caller.accessToken, target.familyId).then().statusCode(204)
        revoke(caller.accessToken, target.familyId).then().statusCode(204) // already gone — same answer

        refresh(caller.refreshToken).then().statusCode(200) // and still no collateral damage
    }

    @Test
    fun anUnknownIdAnswersLikeARealOneAndRevokesNothing() {
        val caller = login()

        revoke(caller.accessToken, UUID.randomUUID().toString()).then().statusCode(204)

        refresh(caller.refreshToken).then().statusCode(200)
    }

    @Test
    fun anotherAccountsFamilyAnswersLikeARealOneAndSurvives() {
        val caller = login()
        val stranger = loginOther()

        // Same 204 as a real id — the response is no oracle for which ids exist...
        revoke(caller.accessToken, stranger.familyId).then().statusCode(204)

        // ...and, the half a status code cannot show: the foreign family is untouched.
        refresh(stranger.refreshToken).then().statusCode(200)
    }

    // --- sign-out-everywhere-else ---------------------------------------------------------------------------

    @Test
    fun signOutEverywhereElseEndsEveryOtherFamilyAndKeepsTheCallers() {
        val caller = login()
        val other1 = login()
        val other2 = login()
        val stranger = loginOther()

        revokeOthers(caller.accessToken).then().statusCode(204)

        refresh(other1.refreshToken).then().statusCode(401).body("code", equalTo("refresh_rejected"))
        refresh(other2.refreshToken).then().statusCode(401).body("code", equalTo("refresh_rejected"))
        // THE assertion, not a side note: the surviving caller session is what separates "sign out everywhere
        // else" from "sign out everywhere", which would satisfy both lines above just as well.
        refresh(caller.refreshToken).then().statusCode(200)
        // The account boundary holds here too.
        refresh(stranger.refreshToken).then().statusCode(200)
    }

    @Test
    fun signOutEverywhereElseIsIdempotentWithNothingElseToEnd() {
        val caller = login()

        revokeOthers(caller.accessToken).then().statusCode(204)
        revokeOthers(caller.accessToken).then().statusCode(204)

        refresh(caller.refreshToken).then().statusCode(200)
    }

    // --- a delegated family is an ordinary family ------------------------------------------------------------

    @Test
    fun aDelegatedFamilyListsRotatesAndRevokesLikeAnyOtherAndTakesNothingWithIt() {
        // ADR 0059 §2 gives a family a second origin beside the login: an unlocked session may mint one for its
        // own account and hand its refresh token to a device it is linking. ADR 0004's claim is that the family
        // is then *ordinary in every other respect*, and this is where that claim is checked rather than
        // asserted — through the session manager's own endpoints, with no pairing channel in sight.
        val granting = login()
        val stranger = loginOther()
        val delegated = sessions.issueDelegated(USER_ID)

        // It lists, next to the login family and marked exactly as not-current...
        val families = listSessions(granting.accessToken)
        assertEquals(
            setOf(granting.familyId, delegated.familyId),
            families.map { it["id"] }.toSet(),
            "a delegated family is listed like any other — it is a device the account can see",
        )
        assertEquals(
            listOf(granting.familyId),
            families.filter { it["current"] == true }.map { it["id"] },
            "and it is not the family this request arrived on",
        )
        assertTrue(
            families.none { it["id"] == stranger.familyId },
            "the account boundary is unchanged by delegation",
        )

        // ...it rotates, which is how the granted device turns one refresh token into a session of its own...
        val rotated = refresh(delegated.refreshToken).then().statusCode(200).extract()
        val rotatedRefresh = rotated.path<String>("refreshToken")

        // ...and it revokes, which is the containment ADR 0059 § Context promises: a linked device can be cut
        // off from the server, whatever key material it already holds.
        revoke(granting.accessToken, delegated.familyId).then().statusCode(204)
        refresh(rotatedRefresh).then().statusCode(401).body("code", equalTo("refresh_rejected"))

        // THE live control: revoking the granted device must not sign out the device that granted it. Without
        // this line, "the delegated family is gone" is satisfied by a revoke that took the whole account.
        refresh(granting.refreshToken).then().statusCode(200)
        assertEquals(
            listOf(granting.familyId),
            listSessions(granting.accessToken).map { it["id"] },
            "the granting session survives, and it is the only one left",
        )
    }

    // --- works-locked-names-never-reach-the-server -----------------------------------------------------------

    @Test
    fun aFamilyCarriesNothingButItsIdTimestampsAndTheCurrentMarker() {
        // The operator-blind criterion is structural, so the honest assertion is over the response SHAPE: a
        // device name, user-agent or client label has no field to arrive in, and this is the line that fails
        // the day someone adds one. (ADR 0004: a per-family name blob would let the operator link a device's
        // re-logins — the mechanism is removed, not guarded.)
        val caller = login()
        login()

        val families = listSessions(caller.accessToken)

        assertTrue(families.isNotEmpty(), "an empty list would satisfy the key-set assertion vacuously")
        families.forEach {
            assertEquals(
                setOf("id", "createdAt", "lastUsedAt", "current"),
                it.keys,
                "a session family exposes exactly these four fields and no name-shaped one",
            )
        }
    }

    @Test
    fun theSessionManagerNeedsASessionAndRefusesWithoutOne() {
        // Only a session — no unlock anywhere in this suite. And without even that, all three endpoints are
        // closed. This is the live control for the test below: the resource's class default is the LENIENT
        // @PermitAll that logout requires, so each of these three carries its own @Authenticated. If one ever
        // loses it, it inherits the lenient default and ships open — and this is the line that says so.
        given().`when`().get("/api/sessions").then().statusCode(401)
        given().`when`().delete("/api/sessions/${UUID.randomUUID()}").then().statusCode(401)
        given().`when`().post("/api/sessions/revoke-others").then().statusCode(401)
    }

    @Test
    fun logoutStillAnswers204ForATokenThatIsNotOne() {
        // Logout must stay a lenient 204 for ANY token — valid, invalid or absent. The session manager moved in
        // next door, and the obvious tidy-up (make the class @Authenticated, mark logout @PermitAll) breaks this
        // line: Quarkus resolves the identity first and answers 401. That failure is why the resource's default
        // is the lenient one and every session-manager method declares @Authenticated itself.
        given().header("Authorization", "Bearer not-a-real-token")
            .`when`().post("/api/logout")
            .then().statusCode(204)
        given().`when`().post("/api/logout").then().statusCode(204)
    }

    // --- helpers --------------------------------------------------------------------------------------------

    private data class Session(val familyId: String, val accessToken: String, val refreshToken: String)

    private fun login(): Session = loginAs(EMAIL, KEYPAIR)

    private fun loginOther(): Session = loginAs(OTHER_EMAIL, OTHER_KEYPAIR)

    private fun loginAs(email: String, keyPair: KeyPair): Session {
        val nonce = given().contentType(ContentType.JSON).body("""{"email":"$email"}""")
            .`when`().post("/api/login/challenge")
            .then().statusCode(200).extract().path<String>("nonce")
        val res = given().contentType(ContentType.JSON)
            .body("""{"email":"$email","signature":"${sign(keyPair, nonce)}"}""")
            .`when`().post("/api/login/verify")
            .then().statusCode(200).extract()
        return Session(res.path("family.id"), res.path("accessToken"), res.path("refreshToken"))
    }

    private fun listSessions(accessToken: String): List<Map<String, Any?>> =
        given().header("Authorization", "Bearer $accessToken")
            .`when`().get("/api/sessions")
            .then().statusCode(200).extract().jsonPath().getList("$", Map::class.java)
            .map { @Suppress("UNCHECKED_CAST") (it as Map<String, Any?>) }

    private fun revoke(accessToken: String, familyId: String) =
        given().header("Authorization", "Bearer $accessToken")
            .`when`().delete("/api/sessions/$familyId")

    private fun revokeOthers(accessToken: String) =
        given().header("Authorization", "Bearer $accessToken")
            .`when`().post("/api/sessions/revoke-others")

    private fun refresh(refreshToken: String) =
        given().contentType(ContentType.JSON).body("""{"refreshToken":"$refreshToken"}""")
            .`when`().post("/api/token/refresh")

    companion object {
        private const val EMAIL = "sessions-test@example.com"
        private const val OTHER_EMAIL = "sessions-stranger@example.com"
        private const val USER_ID = "sessions-test-user"
        private const val OTHER_USER_ID = "sessions-stranger-user"
        private val KEYPAIR: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        private val OTHER_KEYPAIR: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

        private fun seed(userId: String, email: String, keyPair: KeyPair) {
            if (VeyqUser.find("email", email).firstResult() != null) return
            VeyqUser().apply {
                id = userId
                this.email = email
                serverRole = "MEMBER"
                suiteId = "v1"
                identityPublicKey = "aWRlbnRpdHktcHVibGljLWtleQ=="
                wrappedIdentityPrivateKey = "d3JhcHBlZC1pZGVudGl0eS1wcml2YXRlLWtleQ=="
                authPublicKey = b64(keyPair.public.encoded.let { it.copyOfRange(it.size - 32, it.size) })
                createdAt = Instant.now()
            }.persist()
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
