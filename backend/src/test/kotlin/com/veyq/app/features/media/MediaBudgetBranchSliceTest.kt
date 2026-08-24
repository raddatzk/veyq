package com.veyq.app.features.media

import com.veyq.app.features.account.login.SessionService
import com.veyq.app.shared.membership.MembershipEntity
import com.veyq.app.features.admin.tenant.TenantEntity
import com.veyq.app.features.content.NodeEntity
import com.veyq.app.features.spaces.scope.ScopeEntity
import com.veyq.app.shared.blobstore.BlobStore.Companion.chunkKey
import com.veyq.app.shared.blobstore.FakePresigningBlobStore
import com.veyq.app.shared.blobstore.StoreReclaimEntity
import com.veyq.app.shared.identity.VeyqUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Reaches `MediaHandler`'s `verifyBlob`/`BlobVerify.Budget`/`purge` path (cr-media-budget-branch-coverage) —
 * entered by zero tests before this class, hermetic or E2E, because `verifyBlob` short-circuits to `Ok`
 * whenever `!blobStore.canPresign` (`MediaHandler.kt:280`), true for every other hermetic test's
 * [com.veyq.app.shared.blobstore.InMemoryBlobStore]. [FakePresigningBlobStore] answers `canPresign = true` with
 * a scriptable `stat()`, active only in this class via `getEnabledAlternatives()` — every other hermetic test's
 * `BlobStore` wiring (`RecordingBlobStore` → `InMemoryBlobStore`) is unaffected.
 *
 * Not a leak-prevention test: `purge` deliberately leaves the session row standing rather than deleting it
 * (`MediaHandler.kt:287-298`) — `UploadSessionPurge`'s own age-based sweep, a different and already-covered call
 * site, reclaims the same objects even if this class did not exist. What this class actually proves is that
 * `verifyBlob`'s real-vs-declared comparison and the two callers' atomic purge-the-whole-item behavior are
 * correct — neither was exercised by anything before.
 */
@QuarkusTest
@TestProfile(MediaBudgetBranchSliceTest.FakePresignProfile::class)
class MediaBudgetBranchSliceTest {

    class FakePresignProfile : QuarkusTestProfile {
        override fun getEnabledAlternatives(): Set<Class<*>> = setOf(FakePresigningBlobStore::class.java)
    }

    @Inject
    lateinit var sessions: SessionService

    @Inject
    lateinit var fakeStore: FakePresigningBlobStore

    private fun token(userId: String) = "Bearer ${sessions.issue(userId).accessToken}"

    /** Read a started session's storage key + chunk count, then script every chunk's [stat] via [scriptChunk]. */
    private fun scriptSession(uploadId: String, chunkStat: (index: Int, declaredSize: Long) -> Long?) {
        QuarkusTransaction.requiringNew().run {
            val session = UploadSessionEntity.findById(uploadId)!!
            (0 until session.chunkCount).forEach { i ->
                fakeStore.statOf(chunkKey(session.storageKey, i), chunkStat(i, session.byteSize))
            }
        }
    }

    @Test
    fun completeWithOneSessionOverBudgetPurgesTheWholeItemAtomically() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY)
        val original = startOne(userId, scopeId, chunkCount = 1, byteSize = 16, wrap = "b3JpZw==")
        val thumb = startOne(userId, scopeId, chunkCount = 1, byteSize = 16, wrap = "dGh1bWI=")

        // original: the store never got the declared byte — stat comes back short of nothing at all (missing)
        scriptSession(original) { _, _ -> null }
        // thumb: correctly sized on its own — proves it is purged too, not because IT failed, but because its
        // sibling in the same item did (completeOne's own doc comment: "the item is atomic")
        scriptSession(thumb) { _, declared -> declared }

        val (originalKey, thumbKey) = QuarkusTransaction.requiringNew().call {
            UploadSessionEntity.findById(original)!!.storageKey to UploadSessionEntity.findById(thumb)!!.storageKey
        }

        given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body(
                """{"items":[{"nodeId":"$memory","originalUploadId":"$original",""" +
                    """"derivatives":[{"kind":"THUMBNAIL","uploadId":"$thumb"}]}]}""",
            )
            .`when`().post("/api/scopes/$scopeId/media-batch")
            .then().statusCode(200)
            .body("results[0].blobId", nullValue())
            .body("results[0].mediaId", nullValue())
            .body("results[0].error", equalTo("budget_exceeded"))

        QuarkusTransaction.requiringNew().run {
            // both sessions purged (reclaim rows written) — the atomicity claim, not just the failing one
            assert(StoreReclaimEntity.count("storageKey", originalKey) == 1L) {
                "the failing session's chunks must be reclaimed"
            }
            assert(StoreReclaimEntity.count("storageKey", thumbKey) == 1L) {
                "the item is atomic — a sibling session that verified fine must still be purged"
            }
            // purge deliberately does NOT delete the session rows (MediaHandler.kt:287-298) — the opposite
            // assertion shape from every other reclaim-record test in this suite; UploadSessionPurge takes it
            // from here on its own retention clock.
            assertNotNull(UploadSessionEntity.findById(original), "purge leaves the session row standing")
            assertNotNull(UploadSessionEntity.findById(thumb), "purge leaves the session row standing")
        }
    }

    @Test
    fun commitCoverOverBudgetPurgesAndReportsBudgetExceeded() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY)
        val uploadId = startOne(userId, scopeId, chunkCount = 1, byteSize = 16, wrap = "Y292ZXI=")

        // the store reports more bytes than were declared — exactly the mismatch verifyBlob's Budget case checks
        scriptSession(uploadId) { _, declared -> declared + 1 }
        val storageKey = QuarkusTransaction.requiringNew().call { UploadSessionEntity.findById(uploadId)!!.storageKey }

        given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"uploadId":"$uploadId"}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$memory/cover-blob")
            .then().statusCode(400).body("code", equalTo("budget_exceeded"))

        QuarkusTransaction.requiringNew().run {
            assert(StoreReclaimEntity.count("storageKey", storageKey) == 1L) {
                "an over-budget cover commit must reclaim the chunks it staged"
            }
            assertNotNull(UploadSessionEntity.findById(uploadId), "purge leaves the session row standing")
        }
    }

    // --- helpers ---

    private fun startOne(userId: String, scopeId: String, chunkCount: Int, byteSize: Long, wrap: String): String =
        given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"chunkCount":$chunkCount,"byteSize":$byteSize,"wrappedBlobKey":"$wrap"}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch")
            .then().statusCode(200)
            .body("results[0].uploadId", notNullValue())
            .body("results[0].presignedPuts", notNullValue()) // canPresign = true here, unlike every other slice test
            .extract().path("results[0].uploadId")

    private fun seedNode(scopeId: String, kind: String): String {
        val id = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            NodeEntity().apply {
                this.id = id; this.scopeId = scopeId; parentId = scopeId; this.kind = kind
                encryptedName = "bg=="; slugToken = "c2x1Zy0$id".take(120); orderIndex = 0
                wrappedContentKey = if (kind == NodeEntity.MEMORY) "a21lbQ==" else null; createdAt = Instant.now()
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
                id = tenantId; name = "Fam"; domain = "t-$tenantId.example.com"
                ownerEmail = "o-$tenantId@example.com"; status = TenantEntity.ACTIVE; createdAt = Instant.now()
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
                id = userId; email = "u-$userId@example.com"; serverRole = "MEMBER"
                suiteId = "v1"; identityPublicKey = "aWQ="; wrappedIdentityPrivateKey = "aXA="
                authPublicKey = "YXA="; createdAt = Instant.now()
            }.persist()
        }
        return userId
    }
}
