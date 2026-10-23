package me.raddatz.veyq.memories.moderation

import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.blobstore.BlobStore
import me.raddatz.veyq.core.blobstore.BlobStore.Companion.chunkKeys
import me.raddatz.veyq.core.blobstore.RecordingBlobStore
import me.raddatz.veyq.core.blobstore.StoreReclaimEntity
import me.raddatz.veyq.core.blobstore.StoreReclaimSweep
import me.raddatz.veyq.core.moderation.ModerationEventEntity
import me.raddatz.veyq.core.moderation.cases.CaseTestKit
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.call
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.dismiss
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.lift
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.review
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.reviewBlobs
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.take
import me.raddatz.veyq.core.moderation.block.ModerationBlockEntity
import me.raddatz.veyq.core.moderation.hold.HoldPinEntity
import me.raddatz.veyq.core.moderation.report.ReportTestKit.asMember
import me.raddatz.veyq.core.moderation.report.ReportTestKit.body
import me.raddatz.veyq.core.moderation.report.ReportTestKit.item
import me.raddatz.veyq.core.moderation.report.ReportTestKit.openCase
import me.raddatz.veyq.core.moderation.report.ReportTestKit.share
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.sharing.ShareWriteProof
import me.raddatz.veyq.core.spaces.scope.ScopePurge
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.memories.media.BucketEntity
import me.raddatz.veyq.memories.media.MediaDerivativeEntity
import me.raddatz.veyq.memories.media.MediaEntity
import me.raddatz.veyq.memories.media.ShareMediaRead
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.hasItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * A content block and the review over a Memories medium (uc.moderation.take-a-measure step 3, uc.moderation.review-a-case
 * steps 4-5): the CR's phase-2 and phase-3 bites, each with its control on the same predicate. The `%test` store cannot
 * presign, so a byte read that passes every gate answers `store_unavailable`: that is the served control, and a refused
 * one answers before it ever reaches the store.
 */
@QuarkusTest
class ModerationMemoriesSliceTest {

    @Inject
    lateinit var sweep: StoreReclaimSweep

    @Inject
    lateinit var blobStore: BlobStore

    @Inject
    lateinit var scopePurge: ScopePurge

    private val recorder get() = blobStore as RecordingBlobStore

    @Test
    fun blockedContentAnswersEveryByteRead451IsMarkedInEveryListingAndItsSiblingStillServes() {
        val mod = CaseTestKit.moderator()
        val w = guestUpload()
        val sibling = guestMedium(w)
        asMember(w.owner, w.scopeId, body(listOf(item("ITEM", w.medium.mediaId)))).then().statusCode(201)
        val caseId = openCase("ITEM", w.medium.mediaId)!!.id
        val etag = mediaList(w).then().statusCode(200).extract().header("ETag")
        val measure = mod.take(caseId, "CONTENT", targetId = w.medium.mediaId).then().statusCode(201)
            .extract().path<String>("measures[0].id")

        // A member's download: the blocked medium's original and derivative refused, the sibling past the block.
        download(w, listOf(w.medium.originalBlob, w.medium.derivativeBlob, sibling.originalBlob)).then().statusCode(200)
            .body("results[0].error", equalTo("blocked_by_operator"))
            .body("results[1].error", equalTo("blocked_by_operator"))
            .body("results[2].error", equalTo("store_unavailable"))
        // A guest's byte read: 451, the sibling past it.
        given().`when`().get("/api/shares/${w.shareId}/blobs/${w.medium.originalBlob}").then().statusCode(451)
            .body("code", equalTo("blocked_by_operator"))
        given().`when`().get("/api/shares/${w.shareId}/blobs/${sibling.originalBlob}").then().statusCode(503)
        // Both listings keep it and mark it; the open gallery's poll learns it (the item revision moved).
        given().header("Authorization", bearer(w.owner)).header("If-None-Match", etag)
            .`when`().get("/api/scopes/${w.scopeId}/nodes/${w.memory}/media").then().statusCode(200)
            .body("media.find { it.mediaId == '${w.medium.mediaId}' }.blockedByOperator", equalTo(true))
        mediaList(w).then().body("media.find { it.mediaId == '${sibling.mediaId}' }.blockedByOperator", equalTo(false))
        ShareMediaRead.of(w.shareId).then().statusCode(200)
            .body("media.find { it.mediaId == '${w.medium.mediaId}' }.blockedByOperator", equalTo(true))
            .body("media.find { it.mediaId == '${sibling.mediaId}' }.blockedByOperator", equalTo(false))

        // Lifted, the same reads serve it again.
        mod.lift(caseId, measure).then().statusCode(200)
        download(w, listOf(w.medium.originalBlob)).then().body("results[0].error", equalTo("store_unavailable"))
        given().`when`().get("/api/shares/${w.shareId}/blobs/${w.medium.originalBlob}").then().statusCode(503)
        mediaList(w).then().body("media.find { it.mediaId == '${w.medium.mediaId}' }.blockedByOperator", equalTo(false))
    }

    @Test
    fun aBlockedMediumIsDeletedButNeverRestored() {
        val mod = CaseTestKit.moderator()
        val w = guestUpload()
        asMember(w.owner, w.scopeId, body(listOf(item("ITEM", w.medium.mediaId)))).then().statusCode(201)
        val caseId = openCase("ITEM", w.medium.mediaId)!!.id
        val measure = mod.take(caseId, "CONTENT", targetId = w.medium.mediaId).then().statusCode(201)
            .extract().path<String>("measures[0].id")
        given().header("Authorization", bearer(w.owner)).`when`().delete("/api/scopes/${w.scopeId}/media/${w.medium.mediaId}")
            .then().statusCode(204)
        given().header("Authorization", bearer(w.owner)).`when`().post("/api/scopes/${w.scopeId}/media/${w.medium.mediaId}/restore")
            .then().statusCode(451).body("code", equalTo("blocked_by_operator"))
        mod.lift(caseId, measure).then().statusCode(200)
        given().header("Authorization", bearer(w.owner)).`when`().post("/api/scopes/${w.scopeId}/media/${w.medium.mediaId}/restore")
            .then().statusCode(204)
    }

    @Test
    fun aBlockedMemoryIsMarkedForMembersAndGuestsAndBlocksEveryMediumBelowIt() {
        val mod = CaseTestKit.moderator()
        val w = guestUpload()
        asMember(w.owner, w.scopeId, body(listOf(item("NODE", w.memory)))).then().statusCode(201)
        val caseId = openCase("NODE", w.memory)!!.id
        given().`when`().get("/api/shares/${w.shareId}").then().statusCode(200)
            .body("nodes.find { it.id == '${w.memory}' }.blockedByOperator", equalTo(false)) // control
        mod.take(caseId, "CONTENT", targetId = w.memory).then().statusCode(201)
        given().header("Authorization", bearer(w.owner)).`when`().get("/api/scopes/${w.scopeId}/nodes").then().statusCode(200)
            .body("find { it.id == '${w.memory}' }.blockedByOperator", equalTo(true))
        given().`when`().get("/api/shares/${w.shareId}").then().statusCode(200)
            .body("nodes.find { it.id == '${w.memory}' }.blockedByOperator", equalTo(true))
        mediaList(w).then().body("media.find { it.mediaId == '${w.medium.mediaId}' }.blockedByOperator", equalTo(true))
        given().`when`().get("/api/shares/${w.shareId}/blobs/${w.medium.originalBlob}").then().statusCode(451)
    }

    @Test
    fun theReviewReadsTheHeldSnapshotAfterTheLinkTheMemoryAndTheSpaceAreGone() {
        val mod = CaseTestKit.moderator()
        val w = guestUpload()
        asMember(w.owner, w.scopeId, body(listOf(item("NODE", w.memory)))).then().statusCode(201)
        val caseId = openCase("NODE", w.memory)!!.id
        checkReview(mod, caseId, w)
        // The live reads go: the link deleted, the space erased (with every node, medium and blob row in it).
        given().header("Authorization", bearer(w.owner)).`when`().delete("/api/shares/${w.shareId}").then().statusCode(204)
        QuarkusTransaction.requiringNew().run {
            ScopeEntity.findById(w.scopeId)!!.apply { deletedAt = Instant.now().minus(Duration.ofDays(31)) }.persist()
        }
        scopePurge.purge(w.scopeId)
        given().`when`().get("/api/shares/${w.shareId}").then().statusCode(404) // control: the live read is gone
        QuarkusTransaction.requiringNew().run { assertEquals(0L, MediaEntity.count("scopeId", w.scopeId)) }
        checkReview(mod, caseId, w)
        // Every fetch of the report shares is the case's opened event.
        assertEquals(2L, QuarkusTransaction.requiringNew().call { ModerationEventEntity.count("caseId = ?1 and event = ?2", caseId, "OPENED") })
        // Once the hold ended there is nothing to review.
        mod.dismiss(caseId).then().statusCode(200)
        mod.review(caseId).then().statusCode(404).body("code", equalTo("nothing_held"))
        mod.call("listCaseMedia", caseId, "GET", "/api/moderation/cases/$caseId/media").then().statusCode(404)
            .body("code", equalTo("nothing_held"))
    }

    @Test
    fun aDismissalReleasesThePinsAndTheSweepReclaimsWhatWasDeletedMeanwhile() {
        val mod = CaseTestKit.moderator()
        val w = guestUpload()
        asMember(w.owner, w.scopeId, body(listOf(item("ITEM", w.medium.mediaId)))).then().statusCode(201)
        val caseId = openCase("ITEM", w.medium.mediaId)!!.id
        given().header("X-Contributor-Token", w.contributorToken).`when`().delete("/api/shares/${w.shareId}/media/${w.medium.mediaId}")
            .then().statusCode(204)
        makeDue(w.medium.keys())
        recorder.reset()
        sweep.run()
        assertTrue(w.medium.chunkKeys().none { it in recorder.deletedKeys }, "held: the sweep keeps it") // control
        mod.dismiss(caseId).then().statusCode(200)
        assertEquals(0L, QuarkusTransaction.requiringNew().call { HoldPinEntity.count() }, "the pins are gone at once")
        recorder.reset()
        sweep.run()
        assertTrue(recorder.deletedKeys.containsAll(w.medium.chunkKeys()), "the sweep reclaims it: ${recorder.deletedKeys}")
    }

    /** The review answers the held rows: the Memory with its held name and placement, the medium, its blobs. */
    private fun checkReview(mod: CaseTestKit.Moderator, caseId: String, w: World) {
        mod.review(caseId).then().statusCode(200)
            .body("itemKind", equalTo("NODE"))
            .body("reports.size()", equalTo(1))
            .body("reports[0].sealedShare", equalTo("AnJlcG9ydC1zaGFyZQ=="))
            .body("share.role", equalTo("READ"))
            .body("share.wrap", equalTo(null))
            .body("share.rootKind", equalTo("MEMORY"))
            .body("share.nodes.find { it.id == '${w.memory}' }.encryptedName", equalTo("bg=="))
            .body("share.nodes.find { it.id == '${w.memory}' }.parentId", equalTo(null))
            .body("heldLinks.shareId", hasItem(w.shareId))
        mod.call("listCaseMedia", caseId, "GET", "/api/moderation/cases/$caseId/media").then().statusCode(200)
            .body("media.mediaId", hasItem(w.medium.mediaId))
            .body("media.find { it.mediaId == '${w.medium.mediaId}' }.derivatives.size()", equalTo(1))
        // A held blob reaches the store (which cannot presign in %test); one the hold does not hold is left out.
        mod.reviewBlobs(caseId, listOf(w.medium.originalBlob)).then().statusCode(503)
        mod.reviewBlobs(caseId, listOf(UUID.randomUUID().toString())).then().statusCode(200).body("blobs.size()", equalTo(0))
    }

    /**
     * A content block on a medium ends with the medium's purge (ADR 0093 § 6, GDPR Art. 5(1)(e); `MediaStore.purgeMediaRows`,
     * the one body of the member's purge, the retention purge, a guest's delete and the trash purge's media): lifted by no
     * one and logged so, its case's records run to their date instead of standing on nothing. Trashed, it still stands —
     * only the purge erases the medium.
     */
    @Test
    fun aContentBlockOnAMediumEndsWhenTheMediumIsPurged() {
        val mod = CaseTestKit.moderator()
        val w = guestUpload()
        asMember(w.owner, w.scopeId, body(listOf(item("ITEM", w.medium.mediaId)))).then().statusCode(201)
        val caseId = openCase("ITEM", w.medium.mediaId)!!.id
        val measure = mod.take(caseId, "CONTENT", targetId = w.medium.mediaId).then().statusCode(201).extract().path<String>("measures[0].id")
        given().header("Authorization", bearer(w.owner)).`when`().delete("/api/scopes/${w.scopeId}/media/${w.medium.mediaId}")
            .then().statusCode(204)
        assertNotNull(measureRow(measure).activeKey, "control: a trashed medium still stands, and so does its block")
        given().header("Authorization", bearer(w.owner)).`when`().post("/api/scopes/${w.scopeId}/media/${w.medium.mediaId}/purge")
            .then().statusCode(204)
        assertEndedByErasure(caseId, measure, w.medium.mediaId)
    }

    /** The same through a space's purge (`MediaScopeEraser`): the set-based erasure ends the block on every medium it erases. */
    @Test
    fun aContentBlockOnAMediumEndsWithItsSpacesPurge() {
        val mod = CaseTestKit.moderator()
        val w = guestUpload()
        asMember(w.owner, w.scopeId, body(listOf(item("ITEM", w.medium.mediaId)))).then().statusCode(201)
        val caseId = openCase("ITEM", w.medium.mediaId)!!.id
        val measure = mod.take(caseId, "CONTENT", targetId = w.medium.mediaId).then().statusCode(201).extract().path<String>("measures[0].id")
        assertNotNull(measureRow(measure).activeKey, "control: in force before the purge")
        scopePurge.purge(w.scopeId)
        assertEndedByErasure(caseId, measure, w.medium.mediaId)
    }

    private fun measureRow(id: String): ModerationBlockEntity = QuarkusTransaction.requiringNew().call { ModerationBlockEntity.findById(id)!! }

    private fun assertEndedByErasure(caseId: String, measure: String, target: String) {
        val row = measureRow(measure)
        assertNull(row.activeKey, "the block ended with the medium")
        assertNotNull(row.liftedAt)
        assertNull(row.liftedBy, "lifted by no one")
        assertEquals(
            1L,
            QuarkusTransaction.requiringNew().call {
                ModerationEventEntity.count("caseId = ?1 and event = ?2 and targetId = ?3 and moderatorId is null", caseId, "MEASURE_LIFTED", target)
            },
            "the end is logged on its case",
        )
    }

    // --- the world (HoldMemoriesSliceTest's shape) ---------------------------------------------------------------

    private class Medium(
        val mediaId: String, val storageKey: String, val derivativeStorageKey: String, val originalBlob: String, val derivativeBlob: String,
    ) {
        fun keys() = listOf(storageKey, derivativeStorageKey)
        fun chunkKeys() = keys().flatMap { chunkKeys(it, TestBlob.SMALL.chunkCount) }
    }

    private class World(
        val owner: String, val scopeId: String, val memory: String, val shareId: String,
        val identityId: String, val contributorToken: String, val medium: Medium,
    )

    private fun mediaList(w: World) =
        given().header("Authorization", bearer(w.owner)).`when`().get("/api/scopes/${w.scopeId}/nodes/${w.memory}/media")

    private fun download(w: World, blobIds: List<String>) =
        given().header("Authorization", bearer(w.owner)).contentType(ContentType.JSON)
            .body("""{"items":[${blobIds.joinToString(",") { """{"blobId":"$it"}""" }}]}""")
            .`when`().post("/api/scopes/${w.scopeId}/blob-downloads-batch")

    private fun guestUpload(): World {
        val m = seedMemberScope(ScopeEntity.GROUP)
        val memory = seedNode(m.scopeId, "MEMORY", wrappedContentKey = CONTENT_KEY)
        val shareId = share(m.userId, memory, role = "READ_WRITE")
        val identity = given().contentType(ContentType.JSON).header(ShareWriteProof.HEADER, ShareWriteProof.token(shareId))
            .body("""{"nodeEpoch":0,"identityId":"${TestIds.uuid()}","sealedDisplayName":"Z3Vlc3Q="}""")
            .`when`().post("/api/shares/$shareId/identities").then().statusCode(201).extract()
        val w = World(m.userId, m.scopeId, memory, shareId, identity.path("identityId"), identity.path("contributorToken"), Medium("", "", "", "", ""))
        return World(w.owner, w.scopeId, w.memory, w.shareId, w.identityId, w.contributorToken, guestMedium(w))
    }

    private fun guestMedium(w: World): Medium {
        val mediaId = UUID.randomUUID().toString()
        val key = UUID.randomUUID().toString().replace("-", "")
        val derivKey = UUID.randomUUID().toString().replace("-", "")
        var original = ""
        var thumbnail = ""
        QuarkusTransaction.requiringNew().run {
            original = blob(w.scopeId, key)
            thumbnail = blob(w.scopeId, derivKey)
            val bucket = BucketEntity.find("nodeId = ?1 and ownerKey = ?2", w.memory, "share:${w.identityId}").firstResult()
                ?: BucketEntity().apply {
                    id = UUID.randomUUID().toString(); scopeId = w.scopeId; nodeId = w.memory; ownerKey = "share:${w.identityId}"
                    sealedName = "Z3Vlc3Q="; isPrimary = true; createdAt = Instant.now()
                }.also { it.persist() }
            MediaEntity().apply {
                id = mediaId; scopeId = w.scopeId; nodeId = w.memory; blobId = original; shareIdentityId = w.identityId
                createdBy = bucket.ownerKey; bucketId = bucket.id; updatedAt = Instant.now(); wrappedMeta = "bWV0YQ=="
            }.persist()
            MediaDerivativeEntity().apply { id = UUID.randomUUID().toString(); this.mediaId = mediaId; kind = "THUMBNAIL"; blobId = thumbnail }.persist()
        }
        return Medium(mediaId, key, derivKey, original, thumbnail)
    }

    private fun blob(scopeId: String, storageKey: String): String {
        val id = UUID.randomUUID().toString()
        BlobEntity().apply {
            this.id = id; this.scopeId = scopeId; this.storageKey = storageKey
            chunkCount = TestBlob.SMALL.chunkCount; byteSize = TestBlob.SMALL.byteSize; wrappedBlobKey = "Ymxvyi13cmFw"
        }.persist()
        return id
    }

    private fun makeDue(keys: List<String>) = QuarkusTransaction.requiringNew().run {
        StoreReclaimEntity.update("nextAttemptAt = ?1 where storageKey in ?2", Instant.now().minusSeconds(60), keys)
    }
}
