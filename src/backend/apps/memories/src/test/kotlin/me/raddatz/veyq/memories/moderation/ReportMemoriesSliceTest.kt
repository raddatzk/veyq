package me.raddatz.veyq.memories.moderation

import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.moderation.report.ReportTestKit.asGuest
import me.raddatz.veyq.core.moderation.report.ReportTestKit.asMember
import me.raddatz.veyq.core.moderation.report.ReportTestKit.body
import me.raddatz.veyq.core.moderation.report.ReportTestKit.epochOne
import me.raddatz.veyq.core.moderation.report.ReportTestKit.item
import me.raddatz.veyq.core.moderation.report.ReportTestKit.openCase
import me.raddatz.veyq.core.moderation.report.ReportTestKit.report
import me.raddatz.veyq.core.moderation.report.ReportTestKit.share
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.sharing.ShareWriteProof
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.shareKeysJson
import me.raddatz.veyq.memories.media.MediaDerivativeEntity
import me.raddatz.veyq.memories.media.MediaEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Reports of Memories' items and links (uc.moderation.report): a medium is an application's ITEM, located through
 * Memories' `ReportableItems`, and an upload link is a Memory's `WRITE_ONLY` share — both need Memories at runtime.
 */
@QuarkusTest
class ReportMemoriesSliceTest {

    @BeforeEach
    fun epoch() {
        epochOne()
    }

    @Test
    fun aReadGuestReportsAMediumOfTheLinkAndIsRefusedAMediumOfAnotherMemory() {
        val m = seedMemberScope(ScopeEntity.GROUP)
        val memory = seedNode(m.scopeId, "MEMORY", wrappedContentKey = CONTENT_KEY)
        val other = seedNode(m.scopeId, "MEMORY", wrappedContentKey = CONTENT_KEY)
        val inLink = seedMedium(m.scopeId, memory)
        val outside = seedMedium(m.scopeId, other)
        val shareId = share(m.userId, memory)

        asGuest(shareId, body(listOf(item("ITEM", outside.mediaId)))).then().statusCode(404).body("code", equalTo("report_item_not_found"))
        assertNull(openCase("ITEM", outside.mediaId))

        asGuest(shareId, body(listOf(item("ITEM", inLink.mediaId)))).then().statusCode(201)
        val case = openCase("ITEM", inLink.mediaId)!!
        assertEquals(m.scopeId, case.scopeId)
    }

    @Test
    fun aMemberReportsALiveMediumOfTheirSpaceAndNotATrashedOne() {
        val m = seedMemberScope(ScopeEntity.GROUP)
        val memory = seedNode(m.scopeId, "MEMORY", wrappedContentKey = CONTENT_KEY)
        val live = seedMedium(m.scopeId, memory)
        val trashed = seedMedium(m.scopeId, memory)
        QuarkusTransaction.requiringNew().run { MediaEntity.update("deletedAt = ?1 where id = ?2", Instant.now(), trashed.mediaId) }

        asMember(m.userId, m.scopeId, body(listOf(item("ITEM", trashed.mediaId)))).then().statusCode(404)
            .body("code", equalTo("report_item_not_found"))
        asMember(m.userId, m.scopeId, body(listOf(item("ITEM", live.mediaId), item("NODE", memory)))).then().statusCode(201)
        assertNotNull(openCase("ITEM", live.mediaId))
        assertNotNull(openCase("NODE", memory))
    }

    @Test
    fun anUploadLinksGuestReportsTheLinkAndNothingElse() {
        val m = seedMemberScope(ScopeEntity.GROUP)
        val memory = seedNode(m.scopeId, "MEMORY", wrappedContentKey = CONTENT_KEY)
        val medium = seedMedium(m.scopeId, memory)
        val shareId = writeOnlyShare(m.userId, memory)

        asGuest(shareId, body(listOf(item("ITEM", medium.mediaId)))).then().statusCode(404).body("code", equalTo("report_item_not_found"))
        asGuest(shareId, body(listOf(item("NODE", memory)))).then().statusCode(404).body("code", equalTo("report_item_not_found"))
        asGuest(shareId, body(listOf(item("LINK", UUID.randomUUID().toString())))).then().statusCode(400)

        val reportId = asGuest(shareId, body(listOf(item("LINK", null)))).then().statusCode(201).extract().path<String>("reportId")
        assertEquals(shareId, report(reportId)!!.reporterShare)
        val case = openCase("LINK", shareId)!!
        assertEquals(m.scopeId, case.scopeId)
    }

    /** What [seedMedium] persisted. */
    data class Medium(val mediaId: String, val blobId: String, val storageKey: String, val derivativeBlobId: String, val derivativeStorageKey: String)

    /** A medium on [nodeId] with its original and one THUMBNAIL derivative, each its own blob. */
    private fun seedMedium(scopeId: String, nodeId: String): Medium {
        val medium = Medium(
            UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString().replace("-", ""),
            UUID.randomUUID().toString(), UUID.randomUUID().toString().replace("-", ""),
        )
        QuarkusTransaction.requiringNew().run {
            for ((blobId, key) in listOf(medium.blobId to medium.storageKey, medium.derivativeBlobId to medium.derivativeStorageKey)) {
                BlobEntity().apply {
                    id = blobId; this.scopeId = scopeId; storageKey = key
                    chunkCount = TestBlob.SMALL.chunkCount; byteSize = TestBlob.SMALL.byteSize; wrappedBlobKey = "Ymxvyi13cmFw"
                }.persist()
            }
            MediaEntity().apply {
                id = medium.mediaId; this.scopeId = scopeId; this.nodeId = nodeId; blobId = medium.blobId; updatedAt = Instant.now()
            }.persist()
            MediaDerivativeEntity().apply {
                id = UUID.randomUUID().toString(); mediaId = medium.mediaId; kind = "THUMBNAIL"; blobId = medium.derivativeBlobId
            }.persist()
        }
        return medium
    }

    private fun writeOnlyShare(ownerId: String, memory: String): String {
        val shareId = me.raddatz.veyq.core.TestIds.shareId()
        given().header("Authorization", bearer(ownerId)).contentType(ContentType.JSON)
            .body(
                """{"shareId":"$shareId",${ShareWriteProof.field(shareId)},"nodeId":"$memory","role":"WRITE_ONLY",""" +
                    """${shareKeysJson("WRITE_ONLY", memory)},"sealedName":"bmFtZQ==",${ShareWriteProof.recovery()}}""",
            )
            .`when`().post("/api/shares").then().statusCode(201)
        return shareId
    }
}
