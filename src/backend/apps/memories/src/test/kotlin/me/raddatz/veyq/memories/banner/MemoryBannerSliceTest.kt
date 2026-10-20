package me.raddatz.veyq.memories.banner

import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.blob.NodeAttachmentEntity
import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.blobstore.StoreReclaimEntity
import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.content.NodeSubtreePurge
import me.raddatz.veyq.core.moderation.hold.HoldEntity
import me.raddatz.veyq.core.moderation.hold.HoldPinEntity
import me.raddatz.veyq.core.moderation.hold.HoldRowEntity
import me.raddatz.veyq.core.moderation.report.ReportTestKit.asMember
import me.raddatz.veyq.core.moderation.report.ReportTestKit.body
import me.raddatz.veyq.core.moderation.report.ReportTestKit.epochOne
import me.raddatz.veyq.core.moderation.report.ReportTestKit.item
import me.raddatz.veyq.core.moderation.report.ReportTestKit.openCase
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.spaces.scope.ScopePurge
import me.raddatz.veyq.core.testing.CoverKit
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.hamcrest.Matchers.greaterThan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * A Memory's banner (uc.memory.banner, ADR 0102) end to end on the wire: `setMemoryBanner` / `removeMemoryBanner` write
 * the `BANNER` / `BANNER_POSTER` slots and the sealed meta that names them in one transaction, never touch the gallery,
 * and every path core runs for a node's attachments - trash, restore, the trash purge, the scope's erasure, a report's
 * hold - reaches the banner's slots. "Core walks every attachment" is a statement about today's code, so each path is a
 * test that names the slot. The in-memory store answers a committed blob's download with `store_unavailable` and a gone
 * one with `upload_not_found`.
 */
@QuarkusTest
class MemoryBannerSliceTest {

    @Inject
    lateinit var nodePurge: NodeSubtreePurge

    @Inject
    lateinit var scopePurge: ScopePurge

    @Inject
    lateinit var config: VeyqConfig

    // --- the write ---------------------------------------------------------------------------------------------------

    /**
     * Image → video → image → image → removed: each write leaves exactly the slots it names, frees what it replaced (row
     * and reclaim record), and the gallery - its list, both counters - never moves. Bite 2: drop the poster's detach on
     * an image replacing a video and the third step's slots go red. Bite 4: bump the node's item revision in the banner
     * store and the counters go red; the control at the end shows a medium does move them.
     */
    @Test
    fun eachWriteHoldsTheSlotsItNamesAndFreesWhatItReplacesWithoutTouchingTheGallery() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)

        val image = BannerKit.startBanner(userId, scopeId, memory)
        BannerKit.set(userId, scopeId, memory, image.uploadId, wrappedMeta = "bTE=", metaRevision = 0).statusCode(204)
        assertEquals(image.blobId to null, BannerKit.slots(memory))
        assertEquals("bTE=" to 1L, CoverKit.meta(memory), "the meta that names the banner landed in the same write")
        QuarkusTransaction.requiringNew().run {
            val row = NodeAttachmentEntity.find("nodeId = ?1 and slot = ?2", memory, MemoryBanner.SLOT).singleResult()
            assertEquals(userId, row.uploaderUserId, "a member's banner names its member")
            assertNull(row.shareIdentityId, "and no guest identity")
        }
        assertGalleryUntouched(userId, scopeId, memory)

        val video = BannerKit.startBanner(userId, scopeId, memory, TestBlob.ofChunks(2))
        val poster = BannerKit.startPoster(userId, scopeId, memory)
        BannerKit.set(userId, scopeId, memory, video.uploadId, poster.uploadId, expectedBlobId = image.blobId, wrappedMeta = "bTI=", metaRevision = 1)
            .statusCode(204)
        assertEquals(video.blobId to poster.blobId, BannerKit.slots(memory))
        assertFreed(userId, scopeId, image)

        val second = BannerKit.startBanner(userId, scopeId, memory)
        BannerKit.set(
            userId, scopeId, memory, second.uploadId, expectedBlobId = video.blobId, expectedPosterBlobId = poster.blobId,
            wrappedMeta = "bTM=", metaRevision = 2,
        ).statusCode(204)
        assertEquals(second.blobId to null, BannerKit.slots(memory), "an image replacing a video takes its poster with it")
        assertFreed(userId, scopeId, video)
        assertFreed(userId, scopeId, poster)

        val third = BannerKit.startBanner(userId, scopeId, memory)
        BannerKit.set(userId, scopeId, memory, third.uploadId, expectedBlobId = second.blobId, wrappedMeta = "bTQ=", metaRevision = 3)
            .statusCode(204)
        assertEquals(third.blobId to null, BannerKit.slots(memory))
        assertFreed(userId, scopeId, second)

        BannerKit.remove(userId, scopeId, memory, third.blobId, wrappedMeta = "bTU=", metaRevision = 4).statusCode(204)
        assertEquals(null to null, BannerKit.slots(memory))
        assertEquals("bTU=" to 5L, CoverKit.meta(memory))
        assertFreed(userId, scopeId, third)
        assertGalleryUntouched(userId, scopeId, memory)

        // control: a medium in the same Memory moves the counter the banner never moved
        val original = CoverKit.start(userId, scopeId, memory, role = "ORIGINAL", ownerId = UUID.randomUUID().toString())
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"nodeId":"$memory","mediaId":"${TestBlob.ownerOf(original.uploadId)}","originalUploadId":"${original.uploadId}","derivatives":[]}]}""")
            .`when`().post("/api/scopes/$scopeId/media-batch").then().statusCode(200).body("results[0].error", nullValue())
        given().header("Authorization", bearer(userId)).`when`().get("/api/scopes/$scopeId/nodes/$memory/media")
            .then().statusCode(200).body("media.size()", equalTo(1)).body("mediaRevision", greaterThan(0))
    }

    /** A video banner's removal frees both slots; a Chapter carries a banner as a Memory does. */
    @Test
    fun aChaptersVideoBannerGoesWithItsPosterOnRemoval() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val chapter = seedNode(scopeId, MemoriesNodeKinds.CHAPTER, parentId = memory, wrappedContentKey = CONTENT_KEY)
        val video = BannerKit.startBanner(userId, scopeId, chapter)
        val poster = BannerKit.startPoster(userId, scopeId, chapter)
        BannerKit.set(userId, scopeId, chapter, video.uploadId, poster.uploadId).statusCode(204)
        assertEquals("store_unavailable", CoverKit.downloadError(userId, scopeId, poster.blobId), "the poster is a blob of its own")

        BannerKit.remove(userId, scopeId, chapter, video.blobId, poster.blobId, metaRevision = 1).statusCode(204)
        assertEquals(null to null, BannerKit.slots(chapter))
        assertFreed(userId, scopeId, video)
        assertFreed(userId, scopeId, poster)
        // a replay finds both slots empty and still writes the meta: the removal is idempotent
        BannerKit.remove(userId, scopeId, chapter, video.blobId, poster.blobId, wrappedMeta = "cmVwbGF5", metaRevision = 2).statusCode(204)
        assertEquals("cmVwbGF5" to 3L, CoverKit.meta(chapter))
    }

    /**
     * Bite 1, one transaction: a meta that moved writes NOTHING - neither slot, nor the replaced blob's freeing - and the
     * uploads stay open, so the same request on the fresh revision lands. Lever: commit the attach before the meta write
     * (outside its transaction) and "both slots unchanged" goes red.
     */
    @Test
    fun aStaleMetaAttachesNothingAndTheSameUploadsLandOnTheFreshRevision() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val image = BannerKit.startBanner(userId, scopeId, memory)
        BannerKit.set(userId, scopeId, memory, image.uploadId, wrappedMeta = "bTE=").statusCode(204)

        val video = BannerKit.startBanner(userId, scopeId, memory)
        val poster = BannerKit.startPoster(userId, scopeId, memory)
        BannerKit.set(userId, scopeId, memory, video.uploadId, poster.uploadId, expectedBlobId = image.blobId, wrappedMeta = "c3RhbGU=", metaRevision = 0)
            .statusCode(409).body("code", equalTo("stale_revision")).body("wrappedMeta", equalTo("bTE=")).body("metaRevision", equalTo(1))
        assertEquals(image.blobId to null, BannerKit.slots(memory), "both slots unchanged")
        assertEquals("bTE=" to 1L, CoverKit.meta(memory))
        assertEquals("store_unavailable", CoverKit.downloadError(userId, scopeId, image.blobId), "the replaced blob was not freed")
        assertSessionsOpen(video, poster)

        BannerKit.set(userId, scopeId, memory, video.uploadId, poster.uploadId, expectedBlobId = image.blobId, wrappedMeta = "bTI=", metaRevision = 1)
            .statusCode(204)
        assertEquals(video.blobId to poster.blobId, BannerKit.slots(memory))
    }

    /** A slot another member changed meanwhile: `banner_stale` with what both slots hold, and nothing written. */
    @Test
    fun aStaleSlotWritesNothingAndNamesWhatTheSlotsHold() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val video = BannerKit.startBanner(userId, scopeId, memory)
        val poster = BannerKit.startPoster(userId, scopeId, memory)
        BannerKit.set(userId, scopeId, memory, video.uploadId, poster.uploadId, wrappedMeta = "bTE=").statusCode(204)

        // a writer that read no banner, and one that read the banner but not its poster
        val image = BannerKit.startBanner(userId, scopeId, memory)
        for (expected in listOf(null to null, video.blobId to null)) {
            BannerKit.set(userId, scopeId, memory, image.uploadId, expectedBlobId = expected.first, expectedPosterBlobId = expected.second, metaRevision = 1)
                .statusCode(409).body("code", equalTo("banner_stale"))
                .body("currentBlobId", equalTo(video.blobId)).body("currentPosterBlobId", equalTo(poster.blobId))
            assertEquals(video.blobId to poster.blobId, BannerKit.slots(memory))
            assertEquals("bTE=" to 1L, CoverKit.meta(memory))
            assertSessionsOpen(image)
        }
        // a removal that names another blob is refused the same way
        BannerKit.remove(userId, scopeId, memory, UUID.randomUUID().toString(), poster.blobId, metaRevision = 1)
            .statusCode(409).body("code", equalTo("banner_stale"))
        BannerKit.remove(userId, scopeId, memory, video.blobId, null, metaRevision = 1)
            .statusCode(409).body("code", equalTo("banner_stale"))
        assertEquals(video.blobId to poster.blobId, BannerKit.slots(memory))
        // control: the right expectation writes
        BannerKit.set(userId, scopeId, memory, image.uploadId, expectedBlobId = video.blobId, expectedPosterBlobId = poster.blobId, metaRevision = 1)
            .statusCode(204)
    }

    /**
     * Who and where: a member's bearer only (no guest has a route), the scope's own members only, a MEMORY or CHAPTER
     * only, and each upload in its own slot's role. Each negative beside the request that passes the same check.
     */
    @Test
    fun onlyAMemberSetsABannerOnAMemoryWithUploadsStartedForItsSlots() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val folder = seedNode(scopeId, "FOLDER", wrappedContentKey = CONTENT_KEY)
        val banner = BannerKit.startBanner(userId, scopeId, memory)

        // no member's token: no route at all
        BannerKit.set(null, scopeId, memory, banner.uploadId).statusCode(401)
        BannerKit.remove(null, scopeId, memory, banner.blobId).statusCode(401)
        // a member of another space: the node is not found
        val stranger = seedMemberScope().userId
        BannerKit.set(stranger, scopeId, memory, banner.uploadId).statusCode(404).body("code", equalTo("node_not_found"))
        BannerKit.remove(stranger, scopeId, memory, banner.blobId).statusCode(404)
        // a folder takes no banner
        val onFolder = CoverKit.start(userId, scopeId, folder, role = MemoryBanner.SLOT)
        BannerKit.set(userId, scopeId, folder, onFolder.uploadId).statusCode(422).body("code", equalTo("illegal_target"))
        BannerKit.remove(userId, scopeId, folder, onFolder.blobId).statusCode(422).body("code", equalTo("illegal_target"))
        // an upload started for the cover, and a poster started in the banner's role, open nowhere here
        val cover = CoverKit.start(userId, scopeId, memory)
        BannerKit.set(userId, scopeId, memory, cover.uploadId).statusCode(422).body("code", equalTo("upload_mismatch"))
        val notAPoster = BannerKit.startBanner(userId, scopeId, memory)
        BannerKit.set(userId, scopeId, memory, banner.uploadId, notAPoster.uploadId).statusCode(422).body("code", equalTo("upload_mismatch"))
        assertEquals(null to null, BannerKit.slots(memory))
        assertSessionsOpen(banner)

        // control: the owner, on the Memory, with the banner's own upload
        BannerKit.set(userId, scopeId, memory, banner.uploadId).statusCode(204)
        assertEquals(banner.blobId to null, BannerKit.slots(memory))
    }

    // --- every path core runs for an attachment ---------------------------------------------------------------------

    /** Trash keeps both rows and blobs, a restore shows them, and the purge frees both with a reclaim record each. */
    @Test
    fun theBannerGoesToTheTrashComesBackAndIsFreedByThePurge() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val video = BannerKit.startBanner(userId, scopeId, memory)
        val poster = BannerKit.startPoster(userId, scopeId, memory)
        BannerKit.set(userId, scopeId, memory, video.uploadId, poster.uploadId).statusCode(204)

        given().header("Authorization", bearer(userId)).`when`().delete("/api/scopes/$scopeId/nodes/$memory").then().statusCode(204)
        assertEquals(video.blobId to poster.blobId, BannerKit.slots(memory), "the trash keeps the banner for a restore")
        assertEquals(2L, QuarkusTransaction.requiringNew().call { BlobEntity.count("id in ?1", listOf(video.blobId, poster.blobId)) })

        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON).body("""{"slugToken":"YmFubmVyLXJlc3RvcmU="}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$memory/restore").then().statusCode(204)
        assertEquals("store_unavailable", CoverKit.downloadError(userId, scopeId, video.blobId), "restored, the banner reads again")

        given().header("Authorization", bearer(userId)).`when`().delete("/api/scopes/$scopeId/nodes/$memory").then().statusCode(204)
        QuarkusTransaction.requiringNew().run {
            NodeEntity.findById(memory)!!.deletedAt = Instant.now().minus(config.node().trash().retention()).minusSeconds(60)
        }
        nodePurge.run()
        QuarkusTransaction.requiringNew().run {
            assertNull(NodeEntity.findById(memory), "the purge took the node")
            assertEquals(0L, NodeAttachmentEntity.count("nodeId", memory), "and both banner rows")
            assertEquals(0L, BlobEntity.count("id in ?1", listOf(video.blobId, poster.blobId)), "and both blobs")
            for (key in listOf(video.storageKey, poster.storageKey)) {
                assertEquals(1L, StoreReclaimEntity.count("storageKey", key), "a reclaim record for $key")
            }
        }
    }

    /** The space's erasure (an account's, a tenant's, a deleted group's: `ScopePurge`) frees both slots' blobs. */
    @Test
    fun theScopesErasureFreesTheBanner() {
        val (userId, scopeId) = seedMemberScope(ScopeEntity.GROUP)
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val video = BannerKit.startBanner(userId, scopeId, memory)
        val poster = BannerKit.startPoster(userId, scopeId, memory)
        BannerKit.set(userId, scopeId, memory, video.uploadId, poster.uploadId).statusCode(204)
        QuarkusTransaction.requiringNew().run {
            ScopeEntity.findById(scopeId)!!.apply { deletedAt = Instant.now().minus(Duration.ofDays(31)) }.persist()
        }
        scopePurge.purge(scopeId)
        QuarkusTransaction.requiringNew().run {
            assertNull(ScopeEntity.findById(scopeId))
            assertEquals(0L, NodeAttachmentEntity.count("nodeId", memory))
            assertEquals(0L, BlobEntity.count("id in ?1", listOf(video.blobId, poster.blobId)))
            for (key in listOf(video.storageKey, poster.storageKey)) {
                assertEquals(1L, StoreReclaimEntity.count("storageKey", key), "a reclaim record for $key")
            }
        }
    }

    /** A banner is reported with its Memory: a NODE report's hold copies both attachment rows and pins both blobs. */
    @Test
    fun aReportOfTheMemoryHoldsTheBanner() {
        epochOne()
        val (userId, scopeId) = seedMemberScope(ScopeEntity.GROUP)
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val video = BannerKit.startBanner(userId, scopeId, memory)
        val poster = BannerKit.startPoster(userId, scopeId, memory)
        BannerKit.set(userId, scopeId, memory, video.uploadId, poster.uploadId).statusCode(204)

        asMember(userId, scopeId, body(listOf(item("NODE", memory)))).then().statusCode(201)
        val case = openCase("NODE", memory)!!
        QuarkusTransaction.requiringNew().run {
            val hold = HoldEntity.find("caseId", case.id).singleResult()
            val attachments = NodeAttachmentEntity.list("nodeId", memory).map { it.id }.toSet()
            assertEquals(2, attachments.size)
            val copied = HoldRowEntity.list("holdId", hold.id).map { it.sourceTable to it.sourceId }
            for (id in attachments) assertTrue(copied.contains("veyq_node_attachment" to id), "the banner's row $id: $copied")
            for (id in listOf(video.blobId, poster.blobId)) assertTrue(copied.contains("veyq_blob" to id), "its blob $id: $copied")
            val pinned = HoldPinEntity.list("holdId", hold.id).map { it.storageKey }.toSet()
            assertTrue(pinned.containsAll(setOf(video.storageKey, poster.storageKey)), "both pinned: $pinned")
        }
    }

    // --- helpers -----------------------------------------------------------------------------------------------------

    private fun assertGalleryUntouched(userId: String, scopeId: String, nodeId: String) {
        given().header("Authorization", bearer(userId)).`when`().get("/api/scopes/$scopeId/nodes/$nodeId/media")
            .then().statusCode(200).body("media.size()", equalTo(0)).body("mediaRevision", equalTo(0)).body("deleteRevision", equalTo(0))
    }

    /** The blob's row is gone and its store objects are on the reclaim list. */
    private fun assertFreed(userId: String, scopeId: String, started: CoverKit.Started) {
        assertEquals("upload_not_found", CoverKit.downloadError(userId, scopeId, started.blobId), "the blob row of ${started.blobId} is gone")
        assertEquals(1L, QuarkusTransaction.requiringNew().call { StoreReclaimEntity.count("storageKey", started.storageKey) })
    }

    private fun assertSessionsOpen(vararg started: CoverKit.Started) = QuarkusTransaction.requiringNew().run {
        for (s in started) assertNotNull(UploadSessionEntity.findById(s.uploadId), "the upload ${s.uploadId} is still open")
    }
}
