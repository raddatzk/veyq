package me.raddatz.veyq.memories.moderation

import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.blobstore.BlobStore
import me.raddatz.veyq.core.blobstore.BlobStore.Companion.chunkKeys
import me.raddatz.veyq.core.blobstore.RecordingBlobStore
import me.raddatz.veyq.core.blobstore.StoreReclaimEntity
import me.raddatz.veyq.core.blobstore.StoreReclaimSweep
import me.raddatz.veyq.core.moderation.ModerationPurge
import me.raddatz.veyq.core.moderation.hold.HoldEntity
import me.raddatz.veyq.core.moderation.hold.HoldPinEntity
import me.raddatz.veyq.core.moderation.hold.HoldRowEntity
import me.raddatz.veyq.core.moderation.report.ModerationCaseEntity
import me.raddatz.veyq.core.moderation.report.ModerationReportEntity
import me.raddatz.veyq.core.moderation.report.ReportTestKit.asMember
import me.raddatz.veyq.core.moderation.report.ReportTestKit.body
import me.raddatz.veyq.core.moderation.report.ReportTestKit.epochOne
import me.raddatz.veyq.core.moderation.report.ReportTestKit.item
import me.raddatz.veyq.core.moderation.report.ReportTestKit.openCase
import me.raddatz.veyq.core.moderation.report.ReportTestKit.share
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
import org.hamcrest.CoreMatchers.hasItem
import org.hamcrest.CoreMatchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The hold (en.moderation.hold, ADR 0093 § 4) with a Memories medium — the CR's three bites, permanent:
 *
 *  (i) a held medium its guest uploader deletes is gone from every member's and guest's read (the hold is invisible),
 *      and its store objects outlive the sweep run more times than the five attempts it would take to abandon them,
 *      their reclaim rows' `attempts` untouched — the skip is in `StoreReclaimStore.due`, so a held row is never tried;
 *      the lever is that selection: without its `not exists` the sweep hands the keys to the store (red here);
 *  (ii) the hold ends — the sweep removes the objects, the purge drops the snapshot and pins (the positive control of i);
 *  (iii) the reported space is erased — the report, its case, the snapshot and the pins stand (the review reads them).
 */
@QuarkusTest
class HoldMemoriesSliceTest {

    @Inject
    lateinit var sweep: StoreReclaimSweep

    @Inject
    lateinit var blobStore: BlobStore

    @Inject
    lateinit var moderationPurge: ModerationPurge

    @Inject
    lateinit var scopePurge: ScopePurge

    private val recorder get() = blobStore as RecordingBlobStore

    @BeforeEach
    fun epoch() {
        epochOne()
    }

    @Test
    fun aHeldMediumItsUploaderDeletesIsGoneFromEveryReadButItsObjectsOutliveTheSweep() {
        val w = guestUpload()
        asMember(w.owner, w.scopeId, body(listOf(item("ITEM", w.medium.mediaId)))).then().statusCode(201)
        val case = openCase("ITEM", w.medium.mediaId)!!
        val hold = holdOf(case.id)
        assertEquals(setOf(w.medium.storageKey, w.medium.derivativeStorageKey), pinnedKeys(hold.id), "the original and its derivative")
        assertEquals(hold.expiresAt, reportOf(case.id).receivedAt.plus(Duration.ofDays(90)), "a case-open hold ends 90 days after the report")

        // The uploader deletes it: nothing blocks, and nobody can tell it is held.
        given().header("X-Contributor-Token", w.contributorToken).`when`().delete("/api/shares/${w.shareId}/media/${w.medium.mediaId}")
            .then().statusCode(204)
        given().header("Authorization", bearer(w.owner)).`when`().get("/api/scopes/${w.scopeId}/nodes/${w.memory}/media")
            .then().statusCode(200).body("media.mediaId", not(hasItem(w.medium.mediaId)))
        ShareMediaRead.of(w.shareId).then().statusCode(200).body("media.mediaId", not(hasItem(w.medium.mediaId)))
        QuarkusTransaction.requiringNew().run { assertNull(MediaEntity.findById(w.medium.mediaId), "the row is gone, as the user expects") }

        // Its reclaim rows are due, and the sweep runs past what five attempts would take: the objects stay.
        makeDue(w.medium.keys())
        recorder.reset()
        repeat(6) { sweep.run() }
        assertTrue(w.medium.chunkKeys().none { it in recorder.deletedKeys }, "a held object was handed to the store: ${recorder.deletedKeys}")
        val rows = reclaimRows(w.medium.keys())
        assertEquals(2, rows.size, "the reclaim rows wait")
        assertTrue(rows.all { it.attempts == 0 && it.abandonedAt == null }, "a skip is not a failed attempt: ${rows.map { it.attempts }}")
    }

    @Test
    fun aHoldThatEndedLetsTheSweepReclaimAndThePurgeDropsTheSnapshot() {
        val w = guestUpload()
        asMember(w.owner, w.scopeId, body(listOf(item("ITEM", w.medium.mediaId)))).then().statusCode(201)
        val case = openCase("ITEM", w.medium.mediaId)!!
        val hold = holdOf(case.id)
        given().header("X-Contributor-Token", w.contributorToken).`when`().delete("/api/shares/${w.shareId}/media/${w.medium.mediaId}")
            .then().statusCode(204)
        makeDue(w.medium.keys())

        // The hold ends (its 90 days, here moved into the past).
        val ended = Instant.now().minusSeconds(1)
        QuarkusTransaction.requiringNew().run {
            HoldEntity.update("expiresAt = ?1 where id = ?2", ended, hold.id)
            HoldPinEntity.update("expiresAt = ?1 where holdId = ?2", ended, hold.id)
        }
        recorder.reset()
        sweep.run()
        assertTrue(recorder.deletedKeys.containsAll(w.medium.chunkKeys()), "an ended hold keeps nothing: ${recorder.deletedKeys}")
        assertEquals(0, reclaimRows(w.medium.keys()).size)

        moderationPurge.sweep(Instant.now())
        QuarkusTransaction.requiringNew().run {
            assertNull(HoldEntity.findById(hold.id))
            assertEquals(0L, HoldPinEntity.count("holdId", hold.id))
            assertEquals(0L, HoldRowEntity.count("holdId", hold.id))
            assertNotNull(ModerationCaseEntity.findById(case.id), "the case outlives its hold")
        }
        // A further report of the open case takes a new hold, as the case holds none alive.
        val again = guestMedium(w)
        asMember(w.owner, w.scopeId, body(listOf(item("ITEM", again.mediaId)))).then().statusCode(201)
        assertEquals(1L, QuarkusTransaction.requiringNew().call { HoldEntity.count("caseId", openCase("ITEM", again.mediaId)!!.id) })
    }

    @Test
    fun erasingTheReportedSpaceLeavesTheReportTheCaseAndTheSnapshot() {
        val w = guestUpload()
        asMember(w.owner, w.scopeId, body(listOf(item("NODE", w.memory)))).then().statusCode(201)
        val case = openCase("NODE", w.memory)!!
        val hold = holdOf(case.id)
        val copied = QuarkusTransaction.requiringNew().call { HoldRowEntity.list("holdId", hold.id).map { it.sourceTable to it.sourceId } }
        assertTrue(copied.contains("veyq_node" to w.memory), "the node row: $copied")
        assertTrue(copied.contains("veyq_media" to w.medium.mediaId), "Memories' media row: $copied")
        assertTrue(copied.any { it.first == "veyq_media_derivative" }, "its derivative: $copied")
        assertTrue(copied.any { it.first == "veyq_bucket" }, "its bucket: $copied")
        assertTrue(copied.contains("veyq_share_identity" to w.identityId), "the guest identity it names: $copied")
        assertTrue(copied.any { it.first == "veyq_blob" }, "its blobs: $copied")
        assertEquals(setOf(w.medium.storageKey, w.medium.derivativeStorageKey), pinnedKeys(hold.id))

        QuarkusTransaction.requiringNew().run {
            ScopeEntity.findById(w.scopeId)!!.apply { deletedAt = Instant.now().minus(Duration.ofDays(31)) }.persist()
        }
        scopePurge.purge(w.scopeId)

        QuarkusTransaction.requiringNew().run {
            assertNull(ScopeEntity.findById(w.scopeId), "the space is erased")
            assertEquals(0L, BlobEntity.count("scopeId", w.scopeId))
            assertNotNull(ModerationCaseEntity.findById(case.id))
            assertEquals(1L, ModerationReportEntity.count("reporterAccount", w.owner))
            assertEquals(copied.size.toLong(), HoldRowEntity.count("holdId", hold.id), "the snapshot stands")
        }
        assertEquals(setOf(w.medium.storageKey, w.medium.derivativeStorageKey), pinnedKeys(hold.id), "and so do the pins")
        makeDue(w.medium.keys())
        recorder.reset()
        sweep.run()
        assertTrue(w.medium.chunkKeys().none { it in recorder.deletedKeys }, "the erased space's held objects stay")
    }

    // --- the world ---------------------------------------------------------------------------------------------

    private class Medium(val mediaId: String, val storageKey: String, val derivativeStorageKey: String) {
        fun keys() = listOf(storageKey, derivativeStorageKey)
        fun chunkKeys() = keys().flatMap { chunkKeys(it, TestBlob.SMALL.chunkCount) }
    }

    private class World(
        val owner: String, val scopeId: String, val memory: String, val shareId: String,
        val identityId: String, val contributorToken: String, val medium: Medium,
    )

    /** A group's Memory, a READ_WRITE link on it, a named guest, and a medium that guest uploaded (original + thumbnail). */
    private fun guestUpload(): World {
        val m = seedMemberScope(ScopeEntity.GROUP)
        val memory = seedNode(m.scopeId, "MEMORY", wrappedContentKey = CONTENT_KEY)
        val shareId = share(m.userId, memory, role = "READ_WRITE")
        val identity = given().contentType(ContentType.JSON).header(ShareWriteProof.HEADER, ShareWriteProof.token(shareId))
            .body("""{"identityId":"${TestIds.uuid()}","sealedDisplayName":"Z3Vlc3Q="}""")
            .`when`().post("/api/shares/$shareId/identities").then().statusCode(201).extract()
        val w = World(
            m.userId, m.scopeId, memory, shareId, identity.path("identityId"), identity.path("contributorToken"),
            Medium("", "", ""),
        )
        return World(w.owner, w.scopeId, w.memory, w.shareId, w.identityId, w.contributorToken, guestMedium(w))
    }

    /** A medium the world's guest uploaded into its Memory, in the guest's primary bucket. */
    private fun guestMedium(w: World): Medium {
        val medium = Medium(UUID.randomUUID().toString(), UUID.randomUUID().toString().replace("-", ""), UUID.randomUUID().toString().replace("-", ""))
        QuarkusTransaction.requiringNew().run {
            val original = blob(w.scopeId, medium.storageKey)
            val thumbnail = blob(w.scopeId, medium.derivativeStorageKey)
            val bucket = BucketEntity.find("nodeId = ?1 and ownerKey = ?2", w.memory, "share:${w.identityId}").firstResult()
                ?: BucketEntity().apply {
                    id = UUID.randomUUID().toString(); scopeId = w.scopeId; nodeId = w.memory; ownerKey = "share:${w.identityId}"
                    sealedName = "Z3Vlc3Q="; isPrimary = true; createdAt = Instant.now()
                }.also { it.persist() }
            MediaEntity().apply {
                id = medium.mediaId; scopeId = w.scopeId; nodeId = w.memory; blobId = original; shareIdentityId = w.identityId
                createdBy = bucket.ownerKey; bucketId = bucket.id; updatedAt = Instant.now(); wrappedMeta = "bWV0YQ=="
            }.persist()
            MediaDerivativeEntity().apply { id = UUID.randomUUID().toString(); mediaId = medium.mediaId; kind = "THUMBNAIL"; blobId = thumbnail }.persist()
        }
        return medium
    }

    private fun blob(scopeId: String, storageKey: String): String {
        val id = UUID.randomUUID().toString()
        BlobEntity().apply {
            this.id = id; this.scopeId = scopeId; this.storageKey = storageKey
            chunkCount = TestBlob.SMALL.chunkCount; byteSize = TestBlob.SMALL.byteSize; wrappedBlobKey = "Ymxvyi13cmFw"
        }.persist()
        return id
    }

    private fun holdOf(caseId: String): HoldEntity = QuarkusTransaction.requiringNew().call { HoldEntity.find("caseId", caseId).singleResult() }

    private fun reportOf(caseId: String): ModerationReportEntity = QuarkusTransaction.requiringNew().call {
        val reportId = me.raddatz.veyq.core.moderation.report.ReportShareEntity.find("caseId", caseId).firstResult()!!.reportId
        ModerationReportEntity.findById(reportId)!!
    }

    private fun pinnedKeys(holdId: String): Set<String> =
        QuarkusTransaction.requiringNew().call { HoldPinEntity.list("holdId", holdId).map { it.storageKey }.toSet() }

    private fun reclaimRows(keys: List<String>): List<StoreReclaimEntity> =
        QuarkusTransaction.requiringNew().call { StoreReclaimEntity.list("storageKey in ?1", keys) }

    /** Past the not-before every recorded key waits (`veyq.blob.upload.retention`): due on the next tick. */
    private fun makeDue(keys: List<String>) = QuarkusTransaction.requiringNew().run {
        StoreReclaimEntity.update("nextAttemptAt = ?1 where storageKey in ?2", Instant.now().minusSeconds(60), keys)
    }
}
