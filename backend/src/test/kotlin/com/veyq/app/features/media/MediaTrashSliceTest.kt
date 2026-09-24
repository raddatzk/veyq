package com.veyq.app.features.media

import com.veyq.app.features.account.login.SessionService
import com.veyq.app.shared.blobstore.BlobStore
import com.veyq.app.shared.blobstore.BlobStore.Companion.chunkKey
import com.veyq.app.shared.blobstore.RecordingBlobStore
import com.veyq.app.shared.blobstore.StoreReclaimEntity
import com.veyq.app.shared.blobstore.StoreReclaimSweep
import com.veyq.app.shared.config.VeyqConfig
import com.veyq.app.shared.membership.MembershipEntity
import com.veyq.app.features.admin.tenant.TenantEntity
import com.veyq.app.features.content.NodeEntity
import com.veyq.app.features.spaces.scope.ScopeEntity
import com.veyq.app.shared.identity.VeyqUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.hasItem
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Slice tests for the media trash (uc.memory.delete-media, cr-0110): a first-party delete SOFT-deletes a media
 * into a recoverable per-Memory trash, any scope member may trash + restore (audited via deleted_by), and the
 * MediaTrashPurge job hard-deletes past the retention window. Reuses the cr-0107 delete_revision reconcile
 * (a trash bumps BOTH counters + drops the id from the id-set) and the cr-0102 blob-reclaim (the purge). Covers:
 * trash hides + reconciles; restore brings it back; the state=trashed listing carries deletedAt/deletedBy;
 * idempotency; unknown/non-member 404; the retention purge hard-deletes rows AND hands their chunk keys to
 * the store (via the RecordingBlobStore seam — the in-memory store's delete is a no-op that proves nothing).
 */
@QuarkusTest
class MediaTrashSliceTest {

    @Inject
    lateinit var sessions: SessionService

    @Inject
    lateinit var store: MediaStore

    @Inject
    lateinit var trashPurge: MediaTrashPurge

    @Inject
    lateinit var sweep: StoreReclaimSweep

    @Inject
    lateinit var blobStore: BlobStore

    @Inject
    lateinit var config: VeyqConfig

    private fun token(userId: String) = "Bearer ${sessions.issue(userId).accessToken}"

    @Test
    fun trashHidesFromListBumpsBothCountersAndDropsFromIdSet() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY)
        val a = completeOne(userId, scopeId, memory, wrap = "YQ==") // media_revision = 1
        val b = completeOne(userId, scopeId, memory, wrap = "Yg==") // media_revision = 2

        // any member trashes b → soft-delete (204), BOTH counters move (media 2→3, delete 0→1)
        given().header("Authorization", token(userId))
            .`when`().delete("/api/scopes/$scopeId/media/$b")
            .then().statusCode(204)

        // the active list no longer shows b — only a survives
        given().header("Authorization", token(userId))
            .`when`().get("/api/scopes/$scopeId/nodes/$memory/media")
            .then().statusCode(200)
            .body("media.size()", equalTo(1))
            .body("media[0].mediaId", equalTo(a))
            .body("mediaRevision", equalTo(3))
            .body("deleteRevision", equalTo(1))

        // a poll whose sinceDelete (0) lags delete_revision (1) gets the id-set — b is gone from it (reconcile drops the tile)
        given().header("Authorization", token(userId)).header("If-None-Match", "2")
            .queryParam("sinceDelete", 0)
            .`when`().get("/api/scopes/$scopeId/nodes/$memory/media")
            .then().statusCode(200)
            .body("deleteRevision", equalTo(1))
            .body("mediaIds.size()", equalTo(1))
            .body("mediaIds", hasItem(a))
    }

    @Test
    fun restoreBringsItBackIntoTheGallery() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY)
        val a = completeOne(userId, scopeId, memory, wrap = "YQ==")
        val b = completeOne(userId, scopeId, memory, wrap = "Yg==")

        given().header("Authorization", token(userId)).`when`().delete("/api/scopes/$scopeId/media/$b").then().statusCode(204)
        given().header("Authorization", token(userId)).`when`().post("/api/scopes/$scopeId/media/$b/restore").then().statusCode(204)

        // both media are back in the active list
        given().header("Authorization", token(userId))
            .`when`().get("/api/scopes/$scopeId/nodes/$memory/media")
            .then().statusCode(200)
            .body("media.size()", equalTo(2))
            .body("media.mediaId", hasItem(a))
            .body("media.mediaId", hasItem(b))
    }

    @Test
    fun trashedListingCarriesTheAuditAndExcludesFromActive() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY)
        val a = completeOne(userId, scopeId, memory, wrap = "YQ==")
        val b = completeOne(userId, scopeId, memory, wrap = "Yg==")

        given().header("Authorization", token(userId)).`when`().delete("/api/scopes/$scopeId/media/$b").then().statusCode(204)

        // state=trashed lists ONLY the trashed media, carrying the deletion audit (who + when)
        given().header("Authorization", token(userId)).queryParam("state", "trashed")
            .`when`().get("/api/scopes/$scopeId/nodes/$memory/media")
            .then().statusCode(200)
            .body("media.size()", equalTo(1))
            .body("media[0].mediaId", equalTo(b))
            .body("media[0].deletedBy", equalTo(userId))
            .body("media[0].deletedAt", notNullValue())

        // the default (active) listing carries no deletion audit for the live media
        given().header("Authorization", token(userId))
            .`when`().get("/api/scopes/$scopeId/nodes/$memory/media")
            .then().statusCode(200)
            .body("media.size()", equalTo(1))
            .body("media[0].mediaId", equalTo(a))
            .body("media[0].deletedAt", nullValue())
            .body("media[0].deletedBy", nullValue())
    }

    @Test
    fun deleteIsIdempotent() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY)
        val a = completeOne(userId, scopeId, memory, wrap = "YQ==")

        given().header("Authorization", token(userId)).`when`().delete("/api/scopes/$scopeId/media/$a").then().statusCode(204)
        // trashing an already-trashed media is a no-op 204 (no re-bump)
        given().header("Authorization", token(userId)).`when`().delete("/api/scopes/$scopeId/media/$a").then().statusCode(204)
    }

    @Test
    fun deleteUnknownMediaIs404() {
        val (userId, scopeId) = seedMemberScope()
        given().header("Authorization", token(userId))
            .`when`().delete("/api/scopes/$scopeId/media/${UUID.randomUUID()}")
            .then().statusCode(404).body("code", equalTo("media_not_found"))
    }

    @Test
    fun restoreUnknownMediaIs404() {
        val (userId, scopeId) = seedMemberScope()
        given().header("Authorization", token(userId))
            .`when`().post("/api/scopes/$scopeId/media/${UUID.randomUUID()}/restore")
            .then().statusCode(404).body("code", equalTo("media_not_found"))
    }

    @Test
    fun deleteByNonMemberIs404() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY)
        val a = completeOne(userId, scopeId, memory, wrap = "YQ==")
        val outsider = seedUser()
        // a non-member cannot even see the media exists → a non-enumerable 404
        given().header("Authorization", token(outsider))
            .`when`().delete("/api/scopes/$scopeId/media/$a")
            .then().statusCode(404).body("code", equalTo("media_not_found"))
    }

    @Test
    fun unauthenticatedDeleteIs401() {
        given()
            .`when`().delete("/api/scopes/${UUID.randomUUID()}/media/${UUID.randomUUID()}")
            .then().statusCode(401).body("code", equalTo("authentication_failed"))
    }

    @Test
    fun purgeHardDeletesTrashedPastRetention() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY)
        val a = completeOne(userId, scopeId, memory, wrap = "YQ==")
        val b = completeOne(userId, scopeId, memory, wrap = "Yg==")

        given().header("Authorization", token(userId)).`when`().delete("/api/scopes/$scopeId/media/$b").then().statusCode(204)

        // the key the purge must not lose — read while the row that holds it still exists
        val storageKey = QuarkusTransaction.requiringNew().call {
            BlobEntity.findById(MediaEntity.findById(b)!!.blobId)!!.storageKey
        }

        // the retention purge (cutoff just after now) hard-deletes the trashed media row and writes its blob's
        // key to the reclaim list IN THE SAME TRANSACTION (ADR 0062). Asserting the row, not a returned list, is
        // the point of the redesign: a returned list proved the keys were computed and died with the caller.
        // The purge's own return is a count over EVERY scope, so it is not assertable from here — this reads back
        // the one key this case seeded instead, which is the same rule the recorder assertions follow.
        store.purgeTrashedBefore(Instant.now().plusSeconds(60))
        assert(QuarkusTransaction.requiringNew().call { StoreReclaimEntity.count("storageKey", storageKey) } == 1L) {
            "the purge must leave a durable reclaim row for the blob it deleted the only pointer to"
        }

        // b's row is physically gone; a (never trashed) survives
        val (goneB, liveA) = QuarkusTransaction.requiringNew().call {
            MediaEntity.findById(b) to MediaEntity.findById(a)
        }
        assertNull(goneB)
        assert(liveA != null) { "a live media must not be purged" }
    }

    /**
     * The **store half** of the retention purge, which nothing asserted before cr-abandoned-upload-session-reclaim:
     * `purgeHardDeletesTrashedPastRetention` above stops at the reclaim row, so it proves the key was *recorded*
     * and never that it reached the object store — the failure the invariant is actually about (a row-only delete
     * orphans the bytes permanently). This drives the real `@Scheduled` [MediaTrashPurge] **and then**
     * [StoreReclaimSweep], the only component that deletes objects since ADR 0062, and reads what the store was
     * handed off the [RecordingBlobStore] seam. The two cases together are the two halves; neither alone is.
     */
    @Test
    fun retentionPurgeHandsTheTrashedMediasChunkKeysToTheStore() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY)
        val b = completeOne(userId, scopeId, memory, wrap = "Yg==")

        given().header("Authorization", token(userId)).`when`().delete("/api/scopes/$scopeId/media/$b").then().statusCode(204)

        // backdate past the configured retention so the job's own cutoff (not a hand-passed one) selects it
        val storageKey = QuarkusTransaction.requiringNew().call {
            val media = MediaEntity.findById(b)!!
            media.deletedAt = Instant.now().minus(config.media().trash().retention()).minusSeconds(60)
            media.persist()
            BlobEntity.findById(media.blobId)!!.storageKey
        }

        val recorder = blobStore as RecordingBlobStore
        // Drop the JVM-shared reclaim backlog first: the sweep takes a bounded batch of the OLDEST due rows, and
        // this case's own row is the newest in a table every earlier test class has been writing to.
        QuarkusTransaction.requiringNew().run { StoreReclaimEntity.deleteAll() }
        recorder.reset()
        trashPurge.run()
        sweep.run()

        assertNull(QuarkusTransaction.requiringNew().call { MediaEntity.findById(b) })
        assert(chunkKey(storageKey, 0) in recorder.deletedKeys) {
            "the purge + sweep must hand the reclaimed chunk keys to the store; handed: ${recorder.deletedKeys}"
        }
    }

    @Test
    fun purgeTrashedMediaHardDeletesNow() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY)
        val a = completeOne(userId, scopeId, memory, wrap = "YQ==")
        val b = completeOne(userId, scopeId, memory, wrap = "Yg==")

        // trash b, then a member purges it NOW (the expedited hard-delete, cr-0111)
        given().header("Authorization", token(userId)).`when`().delete("/api/scopes/$scopeId/media/$b").then().statusCode(204)

        // the key the purge must not lose — read while the row that holds it still exists
        val storageKey = QuarkusTransaction.requiringNew().call {
            BlobEntity.findById(MediaEntity.findById(b)!!.blobId)!!.storageKey
        }

        given().header("Authorization", token(userId)).`when`().post("/api/scopes/$scopeId/media/$b/purge").then().statusCode(204)

        // b's row is physically gone; restoring it is now a 404; a (never trashed) survives
        val (goneB, liveA) = QuarkusTransaction.requiringNew().call { MediaEntity.findById(b) to MediaEntity.findById(a) }
        assertNull(goneB)
        assert(liveA != null) { "a live media must not be purged" }
        given().header("Authorization", token(userId)).`when`().post("/api/scopes/$scopeId/media/$b/restore").then().statusCode(404)
        assert(QuarkusTransaction.requiringNew().call { StoreReclaimEntity.count("storageKey", storageKey) } == 1L) {
            "the purge must leave a durable reclaim row for the blob it deleted the only pointer to"
        }
    }

    @Test
    fun purgingALiveMediaIs404() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY)
        val a = completeOne(userId, scopeId, memory, wrap = "YQ==")
        // a is LIVE (not trashed) → cannot be purged directly (a live media is deleted only via trash → purge)
        given().header("Authorization", token(userId))
            .`when`().post("/api/scopes/$scopeId/media/$a/purge")
            .then().statusCode(404).body("code", equalTo("media_not_found"))
        val liveA = QuarkusTransaction.requiringNew().call { MediaEntity.findById(a) }
        assert(liveA != null) { "a live media must survive a rejected purge" }
    }

    @Test
    fun purgeByNonMemberIs404() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY)
        val a = completeOne(userId, scopeId, memory, wrap = "YQ==")
        given().header("Authorization", token(userId)).`when`().delete("/api/scopes/$scopeId/media/$a").then().statusCode(204)
        val outsider = seedUser()
        given().header("Authorization", token(outsider))
            .`when`().post("/api/scopes/$scopeId/media/$a/purge")
            .then().statusCode(404)
    }

    // --- helpers (mirror MediaSliceTest) ---

    private fun completeOne(userId: String, scopeId: String, nodeId: String, wrap: String): String {
        val uploadId = startOne(userId, scopeId, wrap)
        return given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"originalUploadId":"$uploadId","nodeId":"$nodeId"}]}""")
            .`when`().post("/api/scopes/$scopeId/media-batch")
            .then().statusCode(200).body("results[0].error", nullValue())
            .extract().path("results[0].mediaId")
    }

    private fun startOne(userId: String, scopeId: String, wrap: String): String =
        given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"items":[${TestBlob.SMALL.declare(wrap)}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch")
            .then().statusCode(200).body("results[0].uploadId", notNullValue())
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
