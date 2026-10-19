package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.blob.CompleteItem
import me.raddatz.veyq.core.blob.NodeAttachmentEntity
import me.raddatz.veyq.core.blob.SharedBlobSpec
import me.raddatz.veyq.core.blob.UploadOpener
import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.blobstore.StoreReclaimEntity
import me.raddatz.veyq.core.blobstore.StoreReclaimStore
import me.raddatz.veyq.core.moderation.ModerationEventEntity
import me.raddatz.veyq.core.moderation.block.BlockKind
import me.raddatz.veyq.core.moderation.block.ModerationBlockEntity
import me.raddatz.veyq.core.moderation.cases.CaseTestKit
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.take
import me.raddatz.veyq.core.moderation.report.ReportTestKit.asMember
import me.raddatz.veyq.core.moderation.report.ReportTestKit.body
import me.raddatz.veyq.core.moderation.report.ReportTestKit.item
import me.raddatz.veyq.core.moderation.report.ReportTestKit.openCase
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.quota.StorageUsage
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.sharing.SharedSubtreeContributor
import me.raddatz.veyq.core.spaces.scope.ScopePurge
import me.raddatz.veyq.core.testing.CoverKit
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.memories.banner.BannerKit
import me.raddatz.veyq.memories.banner.MemoryBanner
import me.raddatz.veyq.memories.media.SharedObjectKit.medium
import me.raddatz.veyq.memories.media.SharedObjectKit.setBanner
import me.raddatz.veyq.memories.media.SharedObjectKit.shared
import me.raddatz.veyq.memories.media.SharedObjectKit.trashAndPurge
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.arc.All
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Shared ownership of a stored object (ADR 0013 § 5): a cover, a banner and a gallery original may be blob rows on one
 * stored object, made by the contract's `SharedBlob` in place of an upload. Pinned here: the shared row names the
 * source's object and nothing is uploaded; the object survives the row that goes first and goes with the last (one
 * reclaim record); quota counts it once; a block reaches the object; a source the node does not hold is refused and
 * nothing is written; and the two layers that keep a live object - the per-row choke point
 * (`BlobUploadStore.deleteBlobRow`) and the sweep's selection (`StoreReclaimStore.due`) - each hold with the other out of
 * the way.
 *
 * Bites (each run 2026-10-19, then restored): (a) `deleteBlobRow` recording unconditionally reddens
 * [theObjectSurvivesTheRowThatGoesFirstAndGoesWithTheLast], [aCoverRemovedFirstLeavesTheMediumsBytes] and both banner
 * cases; (b) the blob-row clause dropped from `due` reddens [theSweepSkipsARecordedKeyThatABlobRowStillNames]; (c)
 * `StorageUsage` summing every row reddens [quotaCountsAnObjectOnce]; (d) `blobBlocked` asking the row alone reddens
 * [aBlockOnTheMediumReachesTheCoverOnItsObject]; (e) `SharedBlobs.persist` without the arms' `holds` reddens
 * [aSourceTheNodeDoesNotHoldIsRefusedAndNothingIsWritten]; (f) `ScopePurge` recording every row reddens
 * [theScopesErasureRecordsAnObjectOnce]; (g) `CaseDecisions.takeIn` handing `erased` no `holding` reddens
 * [aMeasureTakenOnAnErasedMediumHangsOnTheBannersObjectUntilItsLastRowGoes].
 */
@QuarkusTest
class SharedBlobSliceTest {

    @Inject
    lateinit var reclaim: StoreReclaimStore

    @Inject
    lateinit var usage: StorageUsage

    @Inject
    lateinit var scopePurge: ScopePurge

    @Inject
    lateinit var completion: MediaUploadCompletion

    @Inject
    @All
    lateinit var arms: MutableList<SharedSubtreeContributor>

    // --- a cover on a medium's PREVIEW ---------------------------------------------------------------------------------

    @Test
    fun theObjectSurvivesTheRowThatGoesFirstAndGoesWithTheLast() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val m = medium(userId, scopeId, memory)
        val cover = UUID.randomUUID().toString()

        setCover(userId, scopeId, memory, shared(m.preview.blobId, cover)).statusCode(204)
        assertEquals(cover, CoverKit.slot(memory))
        QuarkusTransaction.requiringNew().run {
            val row = BlobEntity.findById(cover)!!
            val source = BlobEntity.findById(m.preview.blobId)!!
            assertEquals(source.storageKey, row.storageKey, "the cover is a row on the preview's object")
            assertEquals(source.byteSize to source.chunkCount, row.byteSize to row.chunkCount)
            assertEquals("c2hhcmVk", row.wrappedBlobKey, "with a wrap of its own")
            assertEquals(0L, UploadSessionEntity.count("blobId", cover), "nothing was uploaded for it")
        }

        trashAndPurge(userId, scopeId, m.mediaId)
        assertEquals("store_unavailable", CoverKit.downloadError(userId, scopeId, cover), "the cover's row outlives the medium")
        assertEquals(0L, reclaims(m.preview.storageKey), "the shared object is not reclaimed while the cover names it")
        assertEquals(1L, reclaims(m.original.storageKey), "the medium's unshared original is")

        CoverKit.remove(userId, scopeId, memory, cover, metaRevision = 1).statusCode(204)
        assertEquals("upload_not_found", CoverKit.downloadError(userId, scopeId, cover))
        assertEquals(1L, reclaims(m.preview.storageKey), "the last row takes the object with it, once")
    }

    @Test
    fun aCoverRemovedFirstLeavesTheMediumsBytes() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val m = medium(userId, scopeId, memory)
        val cover = UUID.randomUUID().toString()
        setCover(userId, scopeId, memory, shared(m.preview.blobId, cover)).statusCode(204)

        CoverKit.remove(userId, scopeId, memory, cover, metaRevision = 1).statusCode(204)
        assertEquals(0L, reclaims(m.preview.storageKey))
        assertEquals("store_unavailable", CoverKit.downloadError(userId, scopeId, m.preview.blobId), "the medium's preview stays")

        trashAndPurge(userId, scopeId, m.mediaId)
        assertEquals(1L, reclaims(m.preview.storageKey))
    }

    // --- a banner on a medium, and a medium on a banner ----------------------------------------------------------------

    @Test
    fun aVideoBannerSharesTheMediumsOriginalAndPreviewAndKeepsThemPastIt() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val m = medium(userId, scopeId, memory)
        val banner = UUID.randomUUID().toString()
        val poster = UUID.randomUUID().toString()

        setBanner(userId, scopeId, memory, shared(m.original.blobId, banner), shared(m.preview.blobId, poster)).statusCode(204)
        assertEquals(banner to poster, BannerKit.slots(memory))
        assertEquals(m.original.storageKey, keyOf(banner))
        assertEquals(m.preview.storageKey, keyOf(poster))

        trashAndPurge(userId, scopeId, m.mediaId)
        assertEquals(0L, reclaims(m.original.storageKey) + reclaims(m.preview.storageKey), "both objects outlive the medium")

        BannerKit.remove(userId, scopeId, memory, banner, poster, metaRevision = 1).statusCode(204)
        assertEquals(1L, reclaims(m.original.storageKey))
        assertEquals(1L, reclaims(m.preview.storageKey))
    }

    @Test
    fun aGalleryOriginalSharesTheVideoBannersObject() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val video = BannerKit.startBanner(userId, scopeId, memory)
        val poster = BannerKit.startPoster(userId, scopeId, memory)
        BannerKit.set(userId, scopeId, memory, video.uploadId, poster.uploadId).statusCode(204)

        val mediaId = UUID.randomUUID().toString()
        val original = UUID.randomUUID().toString()
        val preview = CoverKit.start(userId, scopeId, memory, role = "PREVIEW", ownerId = mediaId)
        completeShared(userId, scopeId, memory, mediaId, shared(video.blobId, original), preview.uploadId)
            .body("results[0].error", nullValue()).body("results[0].blobId", equalTo(original))
        assertEquals(video.storageKey, keyOf(original), "the medium's original is a row on the banner's object")
        given().header("Authorization", bearer(userId)).`when`().get("/api/scopes/$scopeId/nodes/$memory/media")
            .then().statusCode(200).body("media.size()", equalTo(1)).body("media[0].blobId", equalTo(original))

        BannerKit.remove(userId, scopeId, memory, video.blobId, poster.blobId, metaRevision = 1).statusCode(204)
        assertEquals(0L, reclaims(video.storageKey), "the banner's removal leaves the medium's bytes")
        assertEquals(1L, reclaims(poster.storageKey), "and frees its own poster")

        trashAndPurge(userId, scopeId, mediaId)
        assertEquals(1L, reclaims(video.storageKey))
    }

    // --- who may share what --------------------------------------------------------------------------------------------

    @Test
    fun aSourceTheNodeDoesNotHoldIsRefusedAndNothingIsWritten() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val other = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val elsewhere = medium(userId, scopeId, other)
        val trashed = medium(userId, scopeId, memory)
        given().header("Authorization", bearer(userId)).`when`().delete("/api/scopes/$scopeId/media/${trashed.mediaId}").then().statusCode(204)
        val blocked = medium(userId, scopeId, memory)
        block(scopeId, blocked.mediaId)
        val live = medium(userId, scopeId, memory)

        val refused = mapOf(
            "a medium of another node" to shared(elsewhere.preview.blobId),
            "a trashed medium" to shared(trashed.preview.blobId),
            "a blocked medium" to shared(blocked.preview.blobId),
            "an unknown blob" to shared(UUID.randomUUID().toString()),
            "a taken id" to shared(live.preview.blobId, live.original.blobId),
        )
        for ((case, body) in refused) {
            setCover(userId, scopeId, memory, body).statusCode(422).body("code", equalTo("shared_source_invalid"))
            assertEquals(null, CoverKit.slot(memory), case)
        }
        assertEquals(null to null, BannerKit.slots(memory))
        setBanner(userId, scopeId, memory, shared(elsewhere.original.blobId), null).statusCode(422).body("code", equalTo("shared_source_invalid"))
        val mediaId = UUID.randomUUID().toString()
        completeShared(userId, scopeId, memory, mediaId, shared(elsewhere.original.blobId), null)
            .body("results[0].error", equalTo("shared_source_invalid"))
        QuarkusTransaction.requiringNew().run { assertEquals(null, MediaEntity.findById(mediaId), "no medium was written") }

        // exactly one form
        CoverKit.start(userId, scopeId, memory).let { up ->
            given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
                .body("""{"uploadId":"${up.uploadId}","shared":${shared(live.preview.blobId)},"wrappedMeta":"bWV0YQ==","metaRevision":0}""")
                .`when`().put("/api/scopes/$scopeId/nodes/$memory/cover").then().statusCode(400).body("code", equalTo("invalid_request"))
        }
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"wrappedMeta":"bWV0YQ==","metaRevision":0}""")
            .`when`().put("/api/scopes/$scopeId/nodes/$memory/cover").then().statusCode(400).body("code", equalTo("invalid_request"))

        // the seam: a guest's completion never names a shared original, whatever a caller hands it
        val node = QuarkusTransaction.requiringNew().call { NodeEntity.findById(memory)!! }
        val item = CompleteItem(UUID.randomUUID().toString(), memory, null, emptyList(), emptyMap(), specOf(live.preview.blobId))
        assertEquals("shared_source_invalid", completion.complete(scopeId, node, item, UploadOpener.Contributor(UUID.randomUUID().toString()), { true }).error)

        // control on the same predicate: the node's own live medium is a source
        setCover(userId, scopeId, memory, shared(live.preview.blobId)).statusCode(204)
    }

    @Test
    fun aBlockOnTheMediumReachesTheCoverOnItsObject() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val m = medium(userId, scopeId, memory)
        val cover = UUID.randomUUID().toString()
        setCover(userId, scopeId, memory, shared(m.preview.blobId, cover)).statusCode(204)
        assertEquals("store_unavailable", CoverKit.downloadError(userId, scopeId, cover), "served before the block")

        block(scopeId, m.mediaId)
        assertEquals("blocked_by_operator", CoverKit.downloadError(userId, scopeId, cover), "the same bytes under the cover's id")
    }

    /**
     * A block hangs on the stored object (owner 2026-10-19, ADR 0093 § 6): erasing the blocked medium does not end its
     * measure while the banner still holds its objects - the banner stays refused - and the measure ends with the object's
     * last row; a moderator's lift serves the banner again. Bites: `purgeMediaRows` handing no `holding` reddens the first
     * half (the measure ends with the medium, the banner is served); `deleteBlobRow` not calling `objectsGone` reddens the
     * second (the measure stands on nothing).
     */
    @Test
    fun aBlockOnAnErasedMediumHangsOnTheBannersObjectUntilItsLastRowGoes() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val m = medium(userId, scopeId, memory)
        val banner = UUID.randomUUID().toString()
        val poster = UUID.randomUUID().toString()
        setBanner(userId, scopeId, memory, shared(m.original.blobId, banner), shared(m.preview.blobId, poster)).statusCode(204)
        block(scopeId, m.mediaId)
        assertEquals("blocked_by_operator", CoverKit.downloadError(userId, scopeId, banner))

        trashAndPurge(userId, scopeId, m.mediaId)
        QuarkusTransaction.requiringNew().run {
            val row = ModerationBlockEntity.find("targetId", m.mediaId).singleResult()
            assertTrue(row.activeKey != null, "the measure stands while the banner holds the medium's objects")
            assertEquals(setOf(m.original.storageKey, m.preview.storageKey), row.heldKeys)
        }
        assertEquals("blocked_by_operator", CoverKit.downloadError(userId, scopeId, banner), "the banner stays refused")
        assertEquals("blocked_by_operator", CoverKit.downloadError(userId, scopeId, poster))

        BannerKit.remove(userId, scopeId, memory, banner, poster, metaRevision = 1).statusCode(204)
        QuarkusTransaction.requiringNew().run {
            val row = ModerationBlockEntity.find("targetId", m.mediaId).singleResult()
            assertEquals(null, row.activeKey, "the object's last row went: the measure ends with it")
            assertTrue(row.liftedAt != null && row.liftedBy == null, "ended with its target, lifted by no one")
        }
    }

    /**
     * The same where the measure comes after the erasure (owner 2026-10-19, ADR 0093 § 6): a reported medium erased while
     * the banner holds its objects, then blocked through its case - the measure does not end at once but hangs on the
     * objects its case's hold names (`MeasureTargets.survivingObjects`), the banner is refused, and the measure ends with
     * the banner's removal. Control on the same predicate: a reported medium erased with no other row on its objects gets
     * its measure ended at once. Bite: `takeIn` handing `erased` no `holding` reddens the first half (the measure ends at
     * once, the banner is served).
     */
    @Test
    fun aMeasureTakenOnAnErasedMediumHangsOnTheBannersObjectUntilItsLastRowGoes() {
        val mod = CaseTestKit.moderator()
        val (userId, scopeId) = seedMemberScope(ScopeEntity.GROUP)
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val m = medium(userId, scopeId, memory)
        val alone = medium(userId, scopeId, memory)
        val banner = UUID.randomUUID().toString()
        val poster = UUID.randomUUID().toString()
        setBanner(userId, scopeId, memory, shared(m.original.blobId, banner), shared(m.preview.blobId, poster)).statusCode(204)
        asMember(userId, scopeId, body(listOf(item("ITEM", m.mediaId), item("ITEM", alone.mediaId)))).then().statusCode(201)
        val caseId = openCase("ITEM", m.mediaId)!!.id
        val aloneCase = openCase("ITEM", alone.mediaId)!!.id
        trashAndPurge(userId, scopeId, m.mediaId)
        trashAndPurge(userId, scopeId, alone.mediaId)
        assertEquals("store_unavailable", CoverKit.downloadError(userId, scopeId, banner), "served before the measure")

        val measure = mod.take(caseId, "CONTENT", targetId = m.mediaId).then().statusCode(201).extract().path<String>("measures[0].id")
        QuarkusTransaction.requiringNew().run {
            val row = ModerationBlockEntity.findById(measure)!!
            assertTrue(row.activeKey != null, "taken on the erased medium, the measure stands while the banner holds its objects")
            assertEquals(setOf(m.original.storageKey, m.preview.storageKey), row.heldKeys)
        }
        assertEquals("blocked_by_operator", CoverKit.downloadError(userId, scopeId, banner), "the banner is refused")
        assertEquals("blocked_by_operator", CoverKit.downloadError(userId, scopeId, poster))

        // Control: nothing else holds the other medium's objects - its measure ends at once, lifted by no one.
        val ended = mod.take(aloneCase, "CONTENT", targetId = alone.mediaId).then().statusCode(201).extract().path<String>("measures[0].id")
        QuarkusTransaction.requiringNew().run {
            val row = ModerationBlockEntity.findById(ended)!!
            assertEquals(null, row.activeKey, "no object survives the medium: the measure ends at once")
            assertTrue(row.liftedAt != null && row.liftedBy == null && row.heldObjects == null)
        }

        BannerKit.remove(userId, scopeId, memory, banner, poster, metaRevision = 1).statusCode(204)
        QuarkusTransaction.requiringNew().run {
            val row = ModerationBlockEntity.findById(measure)!!
            assertEquals(null, row.activeKey, "the object's last row went: the measure ends with it")
            assertTrue(row.liftedAt != null && row.liftedBy == null, "ended with its target, lifted by no one")
            assertEquals(
                1L,
                ModerationEventEntity.count("caseId = ?1 and event = ?2 and moderatorId is null", caseId, "MEASURE_LIFTED"),
                "its end is logged on its case",
            )
        }
    }

    @Test
    fun aLiftedMeasureServesTheBannerAgain() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val m = medium(userId, scopeId, memory)
        val banner = UUID.randomUUID().toString()
        setBanner(userId, scopeId, memory, shared(m.original.blobId, banner), shared(m.preview.blobId)).statusCode(204)
        block(scopeId, m.mediaId)
        trashAndPurge(userId, scopeId, m.mediaId)
        assertEquals("blocked_by_operator", CoverKit.downloadError(userId, scopeId, banner))

        // A moderator's lift, as CaseDecisions writes it: the active key cleared, the lift dated and signed.
        QuarkusTransaction.requiringNew().run {
            ModerationBlockEntity.find("targetId", m.mediaId).singleResult().apply {
                activeKey = null; liftedAt = Instant.now(); liftedBy = UUID.randomUUID().toString()
            }
        }
        assertEquals("store_unavailable", CoverKit.downloadError(userId, scopeId, banner), "lifted: the banner is served")
    }

    @Test
    fun quotaCountsAnObjectOnce() {
        val s = seedMemberScope()
        val memory = seedNode(s.scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val m = medium(s.userId, s.scopeId, memory)
        val before = QuarkusTransaction.requiringNew().call { usage.tenantUsage(s.tenantId) }
        setCover(s.userId, s.scopeId, memory, shared(m.preview.blobId)).statusCode(204)
        assertEquals(before, QuarkusTransaction.requiringNew().call { usage.tenantUsage(s.tenantId) })
        // control: the measure moves at all - an open upload counts
        CoverKit.start(s.userId, s.scopeId, memory)
        val withSession = QuarkusTransaction.requiringNew().call { usage.tenantUsage(s.tenantId) }
        assertTrue(withSession > before, "an open upload counts")
    }

    // --- the two layers, each with the other out of the way -----------------------------------------------------------

    /** Layer (b) alone: a key recorded directly - past the choke point - is not handed to the store while a row names it. */
    @Test
    fun theSweepSkipsARecordedKeyThatABlobRowStillNames() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val m = medium(userId, scopeId, memory)
        val orphan = "orphan-" + UUID.randomUUID().toString().take(8)
        QuarkusTransaction.requiringNew().run {
            reclaim.record(m.preview.storageKey, 1)
            reclaim.record(orphan, 1)
        }
        val later = Instant.now().plus(Duration.ofDays(3650))
        val due = QuarkusTransaction.requiringNew().call { reclaim.due(later, 10_000).map { it.storageKey }.toSet() }
        assertFalse(m.preview.storageKey in due, "a key a blob row names is never due")
        assertTrue(orphan in due, "control: a key no row names is")
    }

    /** One record per object when the whole space goes, however many rows named it. */
    @Test
    fun theScopesErasureRecordsAnObjectOnce() {
        val (userId, scopeId) = seedMemberScope(ScopeEntity.GROUP)
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val m = medium(userId, scopeId, memory)
        setCover(userId, scopeId, memory, shared(m.preview.blobId)).statusCode(204)
        QuarkusTransaction.requiringNew().run {
            ScopeEntity.findById(scopeId)!!.apply { deletedAt = Instant.now().minus(Duration.ofDays(31)) }.persist()
        }
        scopePurge.purge(scopeId)
        assertEquals(1L, reclaims(m.preview.storageKey))
        QuarkusTransaction.requiringNew().run { assertEquals(0L, NodeAttachmentEntity.count("nodeId", memory)) }
    }

    /** `holds` is the set's predicate for one blob: every arm answers it as `authorizedBlobIds` does. */
    @Test
    fun eachArmAnswersHoldsAsItsSetDoes() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val other = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val m = medium(userId, scopeId, memory)
        val trashed = medium(userId, scopeId, memory)
        given().header("Authorization", bearer(userId)).`when`().delete("/api/scopes/$scopeId/media/${trashed.mediaId}").then().statusCode(204)
        val elsewhere = medium(userId, scopeId, other)
        val cover = UUID.randomUUID().toString()
        setCover(userId, scopeId, memory, shared(m.preview.blobId, cover)).statusCode(204)

        val probes = listOf(m.original.blobId, m.preview.blobId, cover, trashed.original.blobId, elsewhere.original.blobId)
        QuarkusTransaction.requiringNew().run {
            for (arm in arms) {
                val set = arm.authorizedBlobIds(scopeId, listOf(memory))
                for (blob in probes) assertEquals(blob in set, arm.holds(scopeId, memory, blob), "${arm::class.simpleName} on $blob")
            }
            assertTrue(arms.any { it.holds(scopeId, memory, cover) } && arms.any { it.holds(scopeId, memory, m.preview.blobId) })
        }
    }

    // --- helpers -------------------------------------------------------------------------------------------------------

    private fun specOf(source: String): SharedBlobSpec = SharedBlobSpec(source, UUID.randomUUID().toString(), "c2hhcmVk", null)

    private fun setCover(userId: String, scopeId: String, nodeId: String, shared: String): ValidatableResponse =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"shared":$shared,"expectedBlobId":null,"wrappedMeta":"bWV0YQ==","metaRevision":0}""")
            .`when`().put("/api/scopes/$scopeId/nodes/$nodeId/cover").then()

    private fun completeShared(
        userId: String, scopeId: String, nodeId: String, mediaId: String, shared: String, previewUploadId: String?,
    ): ValidatableResponse {
        val derivatives = previewUploadId?.let { """[{"kind":"PREVIEW","uploadId":"$it"}]""" } ?: "[]"
        return given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"nodeId":"$nodeId","mediaId":"$mediaId","originalShared":$shared,"derivatives":$derivatives}]}""")
            .`when`().post("/api/scopes/$scopeId/media-batch").then().statusCode(200)
    }

    private fun block(scopeId: String, mediaId: String) = QuarkusTransaction.requiringNew().run {
        ModerationBlockEntity().apply {
            id = UUID.randomUUID().toString(); kind = BlockKind.CONTENT_ITEM.name; targetId = mediaId; this.scopeId = scopeId
            takenBy = UUID.randomUUID().toString(); takenAt = Instant.now()
            activeKey = ModerationBlockEntity.activeKeyOf(BlockKind.CONTENT_ITEM, mediaId)
        }.persist()
    }

    private fun keyOf(blobId: String): String = QuarkusTransaction.requiringNew().call { BlobEntity.findById(blobId)!!.storageKey }

    private fun reclaims(storageKey: String): Long = QuarkusTransaction.requiringNew().call { StoreReclaimEntity.count("storageKey", storageKey) }
}
