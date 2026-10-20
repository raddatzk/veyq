package me.raddatz.veyq.core.account.login

import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.testing.TestFixtures.newUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Slice tests for `POST /api/token/adopt` — the verb that converts a device-linking grant into the same cookie
 * transport every other web session uses (uc.account.onboard-device /
 * `the-onboarded-device-keeps-the-session-it-was-granted`, ADR 0004 § Transport, ADR 0059 §2).
 *
 * The grant is minted through [SessionService.issueDelegated] rather than by driving the whole pairing
 * ceremony: adoption is keyed on the **token**, never on the channel (`collect` marks the channel spent, so by
 * the time a device could redeem, the row is already terminal — ADR 0004 § Consequences). Going through
 * `PairingSliceTest`'s ceremony would therefore test the ceremony a second time and this verb no better.
 *
 * **The ordering below is load-bearing.** The live control — the adopted session refreshing normally through
 * its cookie — runs *before* the replay that revokes the family, because afterwards the family is dead and
 * every further assertion is vacuum. Each negative carries a positive control on the *same* predicate, and
 * anchors on the concrete observable (a status code, a cookie attribute, the family's own status) rather than
 * on "it failed".
 *
 * Rate-limit posture is deliberately not asserted here: `RateLimitPostureSweepTest` owns it and reddens by
 * itself if this method loses the class-level `@RateLimit(IP)` (ADR 0051).
 */
@QuarkusTest
class TokenAdoptSliceTest {

    @Inject
    lateinit var sessions: SessionService

    @BeforeEach
    fun seedAccount() {
        QuarkusTransaction.requiringNew().run(Runnable {
            if (VeyqUser.find("email", EMAIL).firstResult() == null) {
                newUser(
                    id = USER_ID, email = EMAIL, serverRole = "SERVER_OWNER",
                    authPublicKey = "YWRvcHQtYXV0aC1wdWJsaWMta2V5LTMyLWJ5dGVzISE=",
                ).persist()
            }
        })
    }

    // --- what D2 is: a family id, no body token, and exactly one refresh cookie per scope ------------------

    @Test
    fun adoptingWithSessionScopeReturnsTheFamilyAndSetsOnlyTheSessionCookie() {
        val grant = grant()
        val res = adopt(grant.refreshToken, "SESSION").then().statusCode(200)
            .body("accessToken", notNullValue())
            // The token must NOT come back in the body — carrying it there is exactly the defect this verb
            // exists to correct (ADR 0004 § Context: a body token is one XSS away from exfiltration).
            .body("refreshToken", nullValue())
            .cookie("veyq_refresh_session", notNullValue())
            // The other lifetime is cleared in the same response, so exactly one refresh cookie ever exists.
            .cookie("veyq_refresh", equalTo(""))
            .extract()

        // The family id is the half of D2 that no happy-path "is it signed in?" assertion can see: without it
        // the device's register entry joins to nothing and it can never appear named in uc.account.sessions.
        assertEquals(grant.familyId, res.path("family.id"), "adoption must name the family it took over")

        val cookie = res.detailedCookie("veyq_refresh_session")
        // Anchored on the attribute, not on "a cookie was set" — httpOnly is the whole security claim.
        assertTrue(cookie.isHttpOnly, "the adopted refresh cookie must be httpOnly")
        // A session cookie must OMIT Max-Age entirely; a literal negative Max-Age makes browsers drop it as
        // already-expired (the trap LoginSliceTest paid for on the login path).
        val raw = res.headers().getValues("Set-Cookie").first { it.startsWith("veyq_refresh_session=") }
        assertFalse(raw.contains("Max-Age", ignoreCase = true), "session cookie must omit Max-Age, got: $raw")
    }

    @Test
    fun adoptingWithPersistentScopeSetsOnlyThePersistentCookie() {
        val grant = grant()
        val res = adopt(grant.refreshToken, "PERSISTENT").then().statusCode(200)
            .body("refreshToken", nullValue())
            .cookie("veyq_refresh", notNullValue())
            .cookie("veyq_refresh_session", equalTo("")) // the other lifetime's clearing cookie
            .extract()

        assertEquals(grant.familyId, res.path("family.id"))
        val cookie = res.detailedCookie("veyq_refresh")
        assertTrue(cookie.isHttpOnly, "the adopted refresh cookie must be httpOnly")
        // PERSISTENT is the half that survives a browser restart — the scope choice must actually reach the
        // cookie, not merely pick a name.
        assertTrue(cookie.maxAge > 0, "a persistent cookie must carry a positive Max-Age, got ${cookie.maxAge}")
    }

    @Test
    fun theScopeIsRequiredAndAMissingOneIsRefusedAtTheBoundary() {
        // D3: no default, because a default would be the server answering a question the person holding the new
        // machine owns (ADR 0059 §2 / E5). The control is the same request WITH a scope, which must succeed —
        // otherwise "it 400s" is satisfied equally well by an endpoint that refuses everything.
        // This case is the ONLY thing enforcing D3: the 400 comes from the generated property being non-null,
        // not from a `@NotNull` in the spec (measured — see the note on `sessionScope` in auth.openapi.yaml).
        // So if the generator ever emits a nullable required property, this test is what notices.
        val grant = grant()
        given().contentType(ContentType.JSON).body("""{"refreshToken":"${grant.refreshToken}"}""")
            .`when`().post("/api/token/adopt")
            .then().statusCode(400)
        adopt(grant.refreshToken, "SESSION").then().statusCode(200) // the token was not spent by the refusal
    }

    // --- the live control, and only then the clone case (D5) ------------------------------------------------

    @Test
    fun theAdoptedSessionRefreshesThroughTheCookieAlone() {
        // Without this, every negative below is vacuous: a verb that returned a dead session would satisfy
        // "the replay is refused" perfectly.
        val cookie = adopt(grant().refreshToken, "SESSION").then().statusCode(200)
            .extract().cookie("veyq_refresh_session")
        given().contentType(ContentType.JSON).cookie("veyq_refresh_session", cookie).body("{}")
            .`when`().post("/api/token/refresh")
            .then().statusCode(200)
            .body("accessToken", notNullValue())
            .body("refreshToken", nullValue()) // the rotated token stays in the cookie, as on every web session
            .cookie("veyq_refresh_session", notNullValue())
    }

    @Test
    fun rePresentingTheSpentGrantTokenRevokesTheFamily() {
        val grant = grant()
        val bystander = grant() // a second family of the SAME account: the revoke must be targeted, not blanket
        val cookie = adopt(grant.refreshToken, "SESSION").then().statusCode(200)
            .extract().cookie("veyq_refresh_session")

        // 1. Live control FIRST — the adopted session works. After step 2 the family is gone and this can no
        //    longer be observed at all.
        val rotated = given().contentType(ContentType.JSON).cookie("veyq_refresh_session", cookie).body("{}")
            .`when`().post("/api/token/refresh")
            .then().statusCode(200).extract().cookie("veyq_refresh_session")

        // 2. The grant token was superseded by the adoption, so presenting it again is ADR 0004's clone case.
        adopt(grant.refreshToken, "SESSION").then().statusCode(401).body("code", equalTo("refresh_rejected"))

        // 3. The family is GONE — asserted on the family's own status, not on "the call failed".
        assertEquals(
            SessionFamilyEntity.REVOKED,
            familyStatus(grant.familyId),
            "a replayed grant token must revoke the whole family, not merely be refused",
        )
        // ...and visibly so at the boundary: the CURRENT cookie from step 1, which nothing else touched, no
        // longer rotates. Only a revoked family produces that.
        given().contentType(ContentType.JSON).cookie("veyq_refresh_session", rotated).body("{}")
            .`when`().post("/api/token/refresh")
            .then().statusCode(401)

        // 4. Positive control on the same predicate: the bystander family of the same account is untouched, so
        //    the replay revoked *that* family rather than signing the account out.
        assertEquals(SessionFamilyEntity.ACTIVE, familyStatus(bystander.familyId))
        adopt(bystander.refreshToken, "SESSION").then().statusCode(200)
    }

    // --- a device that already holds a session here may not adopt another (D1) ------------------------------

    @Test
    fun aRequestBearingARefreshCookieIsRefusedAndSpendsNothing() {
        val grant = grant()
        // Both lifetimes, because `SessionCookie.read` looks at both names (persistent wins) — refusing only
        // the one that happens to be checked first would leave the other as a way in.
        val held = adopt(grant().refreshToken, "PERSISTENT").then().statusCode(200)
            .extract().cookie("veyq_refresh")
        val heldSession = adopt(grant().refreshToken, "SESSION").then().statusCode(200)
            .extract().cookie("veyq_refresh_session")

        adoptWithCookie(grant.refreshToken, "SESSION", "veyq_refresh", held)
            .then().statusCode(400).body("code", equalTo("invalid_request"))
        adoptWithCookie(grant.refreshToken, "SESSION", "veyq_refresh_session", heldSession)
            .then().statusCode(400).body("code", equalTo("invalid_request"))

        // The positive control on the same predicate: the SAME request minus the cookie succeeds. It is also
        // what proves the refusal happened *before* the rotation — a 400 that had spent the token would leave
        // this 401 instead, and the endpoint would be a way to burn someone else's grant.
        adopt(grant.refreshToken, "SESSION").then().statusCode(200)
    }

    // --- the failures adoption inherits from refresh, undifferentiated (D1) ---------------------------------

    @Test
    fun anUnknownTokenIsAPlain401AndRevokesNobody() {
        val bystander = grant()
        adopt("not-a-real-token", "SESSION").then().statusCode(401).body("code", equalTo("refresh_rejected"))
        // A bogus token cannot be a DoS lever against a live family.
        assertEquals(SessionFamilyEntity.ACTIVE, familyStatus(bystander.familyId))
        adopt(bystander.refreshToken, "SESSION").then().statusCode(200)
    }

    // --- helpers -------------------------------------------------------------------------------------------

    /** A device-linking grant: a fresh family for this account plus the refresh token handed to the device. */
    private fun grant(): DelegatedSession = sessions.issueDelegated(USER_ID)

    private fun familyStatus(familyId: String): String =
        QuarkusTransaction.requiringNew().call { SessionFamilyEntity.findById(familyId)!!.status }

    private fun adopt(refreshToken: String, scope: String) =
        given().contentType(ContentType.JSON)
            .body("""{"refreshToken":"$refreshToken","sessionScope":"$scope"}""")
            .`when`().post("/api/token/adopt")

    private fun adoptWithCookie(refreshToken: String, scope: String, cookieName: String, cookieValue: String) =
        given().contentType(ContentType.JSON).cookie(cookieName, cookieValue)
            .body("""{"refreshToken":"$refreshToken","sessionScope":"$scope"}""")
            .`when`().post("/api/token/adopt")

    private companion object {
        // Distinct from every other slice test's seed: the %test H2 mem DB is JVM-shared across test classes.
        private const val EMAIL = "token-adopt-test@example.com"
        private const val USER_ID = "518579a2-f475-48b5-8d02-abfbf58e3108"
    }
}
