package me.raddatz.veyq.memories.sharing

import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.moderation.guestwrite.GuestWriteRecordEntity
import me.raddatz.veyq.core.moderation.hold.HoldEntity
import me.raddatz.veyq.core.moderation.hold.HoldPinEntity
import me.raddatz.veyq.core.moderation.hold.HoldRowEntity
import me.raddatz.veyq.core.moderation.hold.HoldTarget
import me.raddatz.veyq.core.moderation.hold.HoldTrigger
import me.raddatz.veyq.core.moderation.hold.Holds
import me.raddatz.veyq.core.sharing.ShareWriteProof
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
import java.time.Duration
import java.time.Instant
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The guest-write record for a Memory's guests (ADR 0093 § 10, en.moderation.guest-write-record): a medium a guest
 * uploads — with the meta it carries — records once, in the attach's transaction, as an `ITEM` naming the medium; a
 * completion that attached nothing records nothing; the guest's delete of its own medium takes the record with it; and a
 * hold on the medium copies the record (Memories' own rows first, phase 100, then core's copy of the record, phase 900).
 */
@QuarkusTest
class GuestWriteRecordMemoriesSliceTest {

    @Inject lateinit var holds: Holds

    private data class Guest(val scopeId: String, val shareId: String, val identityId: String, val token: String)

    private fun guest(): Guest {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val shareId: String = given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body(
                """{${ShareWriteProof.newShare()},"nodeId":"$memory","role":"READ_WRITE","wrap":"d3JhcA==","sealedName":"c2VhbGVk",""" +
                    "${ShareWriteProof.recovery()}}",
            )
            .`when`().post("/api/shares").then().statusCode(201).extract().path("shareId")
        val id = given().contentType(ContentType.JSON).header(ShareWriteProof.HEADER, ShareWriteProof.token(shareId))
            .header("X-Forwarded-For", "203.0.113.30:40000")
            .body("""{"nodeEpoch":0,"identityId":"${TestIds.uuid()}","sealedDisplayName":"bmFtZQ=="}""")
            .`when`().post("/api/shares/$shareId/identities").then().statusCode(201).extract()
        return Guest(scopeId, shareId, id.path("identityId"), id.path("contributorToken"))
    }

    private fun upload(g: Guest, xff: String): String {
        val uploadId: String = given().contentType(ContentType.JSON)
            .body("""{"contributorToken":"${g.token}","items":[${TestBlob.SMALL.declare("Ymxvyi13cmFw")}]}""")
            .`when`().post("/api/shares/${g.shareId}/blob-uploads").then().statusCode(200).extract().path("results[0].uploadId")
        return given().contentType(ContentType.JSON).header("X-Forwarded-For", xff)
            .body("""{"nodeEpoch":0,"contributorToken":"${g.token}","items":[{"mediaId":"${TestBlob.ownerOf(uploadId)}","originalUploadId":"$uploadId","wrappedMeta":"bWV0YQ=="}]}""")
            .`when`().post("/api/shares/${g.shareId}/media").then().statusCode(200)
            .body("results[0].mediaId", notNullValue()).extract().path("results[0].mediaId")
    }

    @Test
    fun aGuestsUploadRecordsOnceAsAnItemAndItsDeleteTakesTheRecordWith() {
        val g = guest()
        val mediaId = upload(g, xff = "198.51.100.66, 203.0.113.31:40001")
        val rows = rows(mediaId)
        assertEquals(1, rows.size, "one attach, one record")
        val row = rows.single()
        assertEquals(listOf("ITEM", null, g.identityId, "203.0.113.31", 40001), listOf(row.objectKind, row.fieldGroup, row.shareIdentityId, row.clientIp, row.clientPort))
        assertEquals(1, rows(g.identityId).size, "the guest naming itself recorded too (control: the identity's own row)")

        given().header("X-Contributor-Token", g.token).`when`().delete("/api/shares/${g.shareId}/media/$mediaId").then().statusCode(204)
        assertEquals(0, rows(mediaId).size, "the guest's delete purged the medium, and its record went with it")
    }

    @Test
    fun aCompletionThatAttachesNothingRecordsNothing() {
        val g = guest()
        val missing = TestIds.uuid()
        given().contentType(ContentType.JSON).header("X-Forwarded-For", "203.0.113.32")
            .body("""{"nodeEpoch":0,"contributorToken":"${g.token}","items":[{"mediaId":"$missing","originalUploadId":"${TestIds.uuid()}"}]}""")
            .`when`().post("/api/shares/${g.shareId}/media").then().statusCode(200).body("results[0].error", equalTo("upload_not_found"))
        assertEquals(0, rows(missing).size)
        assertEquals(1, rows(upload(g, xff = "203.0.113.32")).size, "control: one that attaches records")
    }

    @Test
    fun aHoldOnTheMediumCopiesItsRecord() {
        val g = guest()
        val mediaId = upload(g, xff = "203.0.113.33:40003")
        val holdId = QuarkusTransaction.requiringNew().call {
            holds.take(null, HoldTarget.Item(g.scopeId, mediaId), HoldTrigger.CASE_OPEN, Instant.now().plus(Duration.ofDays(1)))
        }
        try {
            val copies = QuarkusTransaction.requiringNew().call {
                HoldRowEntity.list("holdId = ?1 and sourceTable = ?2", holdId, "veyq_guest_write_record").map { it.payload }
            }
            assertTrue(copies.any { it.contains(mediaId) && it.contains("40003") }, "the medium's record is in the hold: $copies")
        } finally {
            QuarkusTransaction.requiringNew().run {
                HoldRowEntity.delete("holdId", holdId); HoldPinEntity.delete("holdId", holdId); HoldEntity.deleteById(holdId)
            }
        }
    }

    private fun rows(objectId: String): List<GuestWriteRecordEntity> =
        QuarkusTransaction.requiringNew().call { GuestWriteRecordEntity.list("objectId", objectId) }
}
