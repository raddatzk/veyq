package me.raddatz.veyq.memories.sharing

import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Test
import me.raddatz.veyq.core.sharing.SharePasswordAttemptLimiter
import me.raddatz.veyq.core.sharing.ShareWriteProof

/**
 * The 429 proof for the per-share **contributor-identity** bound added with the declarative posture (ADR 0051):
 * `createShareIdentity` is the one guest call that writes a row **before any upload exists**, so without a bound an
 * unauthenticated link holder can grow the identity table on its own.
 *
 * The share here carries **no password** on purpose. That keeps [SharePasswordAttemptLimiter] entirely out of the
 * picture, so a 429 can only come from the new identity budget — with a password-protected share the two bounds
 * would be indistinguishable from the outside and this test would prove nothing about the one it names.
 *
 * Pins three things:
 *  - the bound bites (N creates succeed, the N+1st is `429 rate_limited`),
 *  - it is **per share** (an exhausted share does not spend another's budget),
 *  - it is a **separate budget from uploads** — a share whose identity budget is gone can still be uploaded to,
 *    which is what stops one abusive axis from taking the other down with it.
 */
@QuarkusTest
@TestProfile(ShareIdentityBoundTest.LowIdentityBudgetProfile::class)
class ShareIdentityBoundTest {

    class LowIdentityBudgetProfile : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf(
            "veyq.share.identity.rate.max-per-window" to "3",
            "veyq.share.identity.rate.window-seconds" to "60",
        )
    }

    private val wrap = "d3JhcC1rLW1lbS11bmRlci1zaGFyZQ=="
    private val sealedName = "c2VhbGVkLW5hbWU="

    @Test
    fun theCreateAfterTheAllowanceIs429() {
        val (userId, scopeId) = seedMemberScope()
        val shareId = createShare(userId, seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY))

        repeat(3) { createIdentity(shareId).then().statusCode(201) }
        createIdentity(shareId).then().statusCode(429).body("code", equalTo("rate_limited"))
    }

    @Test
    fun oneExhaustedShareDoesNotSpendAnothersBudget() {
        val (userId, scopeId) = seedMemberScope()
        val exhausted = createShare(userId, seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY))
        val other = createShare(userId, seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY))

        repeat(3) { createIdentity(exhausted).then().statusCode(201) }
        createIdentity(exhausted).then().statusCode(429)

        createIdentity(other).then().statusCode(201) // its own budget, untouched
    }

    @Test
    fun theIdentityBudgetIsSeparateFromTheUploadBudget() {
        val (userId, scopeId) = seedMemberScope()
        val shareId = createShare(userId, seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY))

        val contributorToken = createIdentity(shareId).then().statusCode(201)
            .extract().path<String>("contributorToken")
        repeat(2) { createIdentity(shareId).then().statusCode(201) }
        createIdentity(shareId).then().statusCode(429) // the identity budget is gone...

        // ...but the upload budget is a different window, so a contributor who already named itself still works.
        given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"$contributorToken","items":[${TestBlob.SMALL.declare("a2V5")}]}""")
            .`when`().post("/api/shares/$shareId/blob-uploads")
            .then().statusCode(200)
    }

    // --- helpers (mirroring SharePasswordAttemptBoundTest) ---

    private fun createIdentity(shareId: String) =
        given().contentType(ContentType.JSON).body("""{"nodeEpoch":0,"identityId":"${TestIds.uuid()}","sealedDisplayName":"$sealedName"}""")
            .header(ShareWriteProof.HEADER, ShareWriteProof.token(shareId)).`when`().post("/api/shares/$shareId/identities")

    private fun createShare(userId: String, nodeId: String): String =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"$nodeId","role":"READ_WRITE","wrap":"$wrap","sealedName":"$sealedName",${ShareWriteProof.recovery()}}""")
            .`when`().post("/api/shares")
            .then().statusCode(201).extract().path("shareId")
}
