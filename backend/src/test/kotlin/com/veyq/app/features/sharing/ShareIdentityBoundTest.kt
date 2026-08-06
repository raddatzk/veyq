package com.veyq.app.features.sharing

import com.veyq.app.features.account.login.SessionService
import com.veyq.app.features.admin.tenant.TenantEntity
import com.veyq.app.features.content.NodeEntity
import com.veyq.app.features.spaces.scope.ScopeEntity
import com.veyq.app.shared.identity.VeyqUser
import com.veyq.app.shared.membership.MembershipEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

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

    @Inject
    lateinit var sessions: SessionService

    private val wrap = "d3JhcC1rLW1lbS11bmRlci1zaGFyZQ=="
    private val sealedName = "c2VhbGVkLW5hbWU="

    @Test
    fun theCreateAfterTheAllowanceIs429() {
        val (userId, scopeId) = seedMemberScope()
        val shareId = createShare(userId, seedMemory(scopeId))

        repeat(3) { createIdentity(shareId).then().statusCode(201) }
        createIdentity(shareId).then().statusCode(429).body("code", equalTo("rate_limited"))
    }

    @Test
    fun oneExhaustedShareDoesNotSpendAnothersBudget() {
        val (userId, scopeId) = seedMemberScope()
        val exhausted = createShare(userId, seedMemory(scopeId))
        val other = createShare(userId, seedMemory(scopeId))

        repeat(3) { createIdentity(exhausted).then().statusCode(201) }
        createIdentity(exhausted).then().statusCode(429)

        createIdentity(other).then().statusCode(201) // its own budget, untouched
    }

    @Test
    fun theIdentityBudgetIsSeparateFromTheUploadBudget() {
        val (userId, scopeId) = seedMemberScope()
        val shareId = createShare(userId, seedMemory(scopeId))

        val contributorToken = createIdentity(shareId).then().statusCode(201)
            .extract().path<String>("contributorToken")
        repeat(2) { createIdentity(shareId).then().statusCode(201) }
        createIdentity(shareId).then().statusCode(429) // the identity budget is gone...

        // ...but the upload budget is a different window, so a contributor who already named itself still works.
        given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"$contributorToken","items":[{"chunkCount":1,"byteSize":16,"wrappedBlobKey":"a2V5"}]}""")
            .`when`().post("/api/shares/$shareId/blob-uploads")
            .then().statusCode(200)
    }

    // --- helpers (mirroring SharePasswordAttemptBoundTest) ---

    private fun createIdentity(shareId: String) =
        given().contentType(ContentType.JSON).body("""{"sealedDisplayName":"$sealedName"}""")
            .`when`().post("/api/shares/$shareId/identities")

    private fun createShare(userId: String, nodeId: String): String =
        given().header("Authorization", "Bearer ${sessions.issue(userId).accessToken}").contentType(ContentType.JSON)
            .body("""{"nodeId":"$nodeId","role":"CONTRIBUTOR","wrap":"$wrap","sealedName":"$sealedName"}""")
            .`when`().post("/api/shares")
            .then().statusCode(201).extract().path("shareId")

    private fun seedMemory(scopeId: String): String {
        val id = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            NodeEntity().apply {
                this.id = id; this.scopeId = scopeId; parentId = scopeId; kind = NodeEntity.MEMORY
                encryptedName = "bg=="; slugToken = "c2x1Zy0$id".take(120); orderIndex = 0
                wrappedContentKey = "a21lbQ=="; createdAt = Instant.now()
            }.persist()
        }
        return id
    }

    private fun seedMemberScope(): Pair<String, String> {
        val userId = seedUser()
        val tenantId = UUID.randomUUID().toString()
        val scopeId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            TenantEntity().apply {
                id = tenantId; name = "Fam"; domain = "ib-$tenantId.example.com"
                ownerEmail = "ib-$tenantId@example.com"; status = TenantEntity.ACTIVE; createdAt = Instant.now()
            }.persist()
            ScopeEntity().apply {
                id = scopeId; this.tenantId = tenantId; kind = ScopeEntity.PERSONAL; createdAt = Instant.now()
            }.persist()
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); this.userId = userId; this.tenantId = tenantId
                this.scopeId = scopeId; role = MembershipEntity.OWNER; wrappedGrant = "Z3JhbnQ="; createdAt = Instant.now()
            }.persist()
        }
        return userId to scopeId
    }

    private fun seedUser(): String {
        val userId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            VeyqUser().apply {
                id = userId; email = "ib-$userId@example.com"; serverRole = "MEMBER"
                suiteId = "v1"; identityPublicKey = "aWQ="; wrappedIdentityPrivateKey = "aXA="
                authPublicKey = "YXA="; createdAt = Instant.now()
            }.persist()
        }
        return userId
    }
}
