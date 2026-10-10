package me.raddatz.veyq.memories.sharing

import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.junit.QuarkusTestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Base64
import me.raddatz.veyq.core.sharing.ShareWriteProof

/**
 * The permanent guard for cr-0165: the online brute-force bound ADR 0037 relies on actually exists — a **per-share**
 * limit on **wrong** password attempts (not the per-IP bound the ADR wrongly claimed; behind a proxy every guest is
 * one IP). Drives the budget down to 3/window via its own @TestProfile (mirroring AuthRateLimitTest), leaving the
 * suite default untouched.
 *
 * Pins the four things that make the bound correct rather than merely present:
 *  - **N wrong → 403, the N+1st → 429** (the bound bites),
 *  - **a tokenless (MISSING) resolve never consumes budget** (else every honest guest, who fetches the challenge
 *    first, would exhaust it) — decision 2, the one invisible in an "add a rate limit" framing,
 *  - **a valid token is unaffected by another share's exhausted budget** (the per-share axis),
 *  - **a guest WRITE op inherits the bound** (cr-0164 gave passwordCheck six call sites; the bound rides all of them).
 */
@QuarkusTest
@TestProfile(SharePasswordAttemptBoundTest.LowBudgetProfile::class)
class SharePasswordAttemptBoundTest {

    class LowBudgetProfile : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf(
            "veyq.share.password.rate.max-per-window" to "3",
            "veyq.share.password.rate.window-seconds" to "60",
        )
    }

    private val wrap = "d3JhcC1rLW1lbS11bmRlci1zaGFyZQ=="
    private val sealedName = "c2VhbGVkLW5hbWU="
    private val pwSalt = "cHctc2FsdA=="
    private val pwToken = "cHctdG9rZW4="
    private val pwVerifier = sha256b64(pwToken)
    private val wrongToken = "d3JvbmctdG9rZW4="

    @Test
    fun nWrongAttemptsThen429_perShare() {
        val (userId, scopeId) = seedMemberScope()
        val shareId = createWithPassword(userId, seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY), "READ")

        // budget = 3: the first three wrong tokens are 403, the fourth is 429
        repeat(3) {
            given().header("X-Share-Password", wrongToken).`when`().get("/api/shares/$shareId")
                .then().statusCode(403).body("code", equalTo("share_password_incorrect"))
        }
        given().header("X-Share-Password", wrongToken).`when`().get("/api/shares/$shareId")
            .then().statusCode(429).body("code", equalTo("rate_limited"))

        // the CORRECT password is admitted before the counter is touched → never locked out, even now
        given().header("X-Share-Password", pwToken).`when`().get("/api/shares/$shareId")
            .then().statusCode(200).body("wrap", equalTo(wrap))
    }

    @Test
    fun missingTokenNeverConsumesBudget() {
        val (userId, scopeId) = seedMemberScope()
        val shareId = createWithPassword(userId, seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY), "READ")

        // a tokenless resolve is the honest guest's challenge fetch — fire it far more than the budget
        repeat(10) { given().`when`().get("/api/shares/$shareId").then().statusCode(200).body("passwordRequired", equalTo(true)) }
        // budget is untouched: a wrong token is still 403 (not 429), and the first three still fit
        given().header("X-Share-Password", wrongToken).`when`().get("/api/shares/$shareId")
            .then().statusCode(403).body("code", equalTo("share_password_incorrect"))
    }

    @Test
    fun perShareAxis_oneExhaustedShareDoesNotBlockAnother() {
        val (userId, scopeId) = seedMemberScope()
        val exhausted = createWithPassword(userId, seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY), "READ")
        val other = createWithPassword(userId, seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY), "READ")

        repeat(4) { given().header("X-Share-Password", wrongToken).`when`().get("/api/shares/$exhausted") }
        given().header("X-Share-Password", wrongToken).`when`().get("/api/shares/$exhausted").then().statusCode(429)

        // the other share has its own budget — a valid token unlocks, a wrong one is still 403 (not 429)
        given().header("X-Share-Password", pwToken).`when`().get("/api/shares/$other").then().statusCode(200)
        given().header("X-Share-Password", wrongToken).`when`().get("/api/shares/$other")
            .then().statusCode(403).body("code", equalTo("share_password_incorrect"))
    }

    @Test
    fun writePathInheritsTheBound() {
        val (userId, scopeId) = seedMemberScope()
        val shareId = createWithPassword(userId, seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY), "READ_WRITE")

        // exhaust the budget via the WRITE endpoint (create-identity) — the bound rides all six passwordCheck sites
        repeat(3) {
            given().header("X-Share-Password", wrongToken).contentType(ContentType.JSON).body("""{"identityId":"${TestIds.uuid()}","sealedDisplayName":"$sealedName"}""")
                .header(ShareWriteProof.HEADER, ShareWriteProof.token(shareId)).`when`().post("/api/shares/$shareId/identities")
                .then().statusCode(403).body("code", equalTo("share_password_incorrect"))
        }
        given().header("X-Share-Password", wrongToken).contentType(ContentType.JSON).body("""{"identityId":"${TestIds.uuid()}","sealedDisplayName":"$sealedName"}""")
            .header(ShareWriteProof.HEADER, ShareWriteProof.token(shareId)).`when`().post("/api/shares/$shareId/identities")
            .then().statusCode(429).body("code", equalTo("rate_limited"))
    }

    // --- helpers (mirroring SharePasswordSliceTest) ---

    private fun sha256b64(tokenB64: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(tokenB64)))

    private fun createWithPassword(userId: String, nodeId: String, role: String): String =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"$nodeId","role":"$role","wrap":"$wrap","sealedName":"$sealedName",${ShareWriteProof.recovery()},"passwordRequired":true,"pwSalt":"$pwSalt","pwVerifier":"$pwVerifier"}""")
            .`when`().post("/api/shares")
            .then().statusCode(201).extract().path("shareId")
}
