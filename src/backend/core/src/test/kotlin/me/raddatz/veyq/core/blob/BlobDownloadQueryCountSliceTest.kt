package me.raddatz.veyq.core.blob

import me.raddatz.veyq.core.moderation.block.BlockKind
import me.raddatz.veyq.core.moderation.block.ModerationBlockEntity
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.persistence.CoreStatementCount
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedAttachment
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * A `blob-downloads-batch` is answered with the same number of SQL statements whatever the number of ids it carries
 * (inbox #537): the batch's blob rows and its space's content blocks are each read once, never once per id. Measured
 * before the fix at 12 + 2 per id — 524 statements for a batch of 256 thumbnails, ≈ 116 a request on the profile's
 * 1,000-photo Chapter. A batch of one against a batch of sixty, in a space with no block (every gallery's case) and in
 * one where a content block is in force (where each id also walked every arm under the blocked node). The count
 * includes the request's own reads (the session, the access gate), which do not grow with the batch either. `%test`'s
 * store presigns nothing, so every unblocked id answers `store_unavailable` — after both reads this pins.
 */
@QuarkusTest
class BlobDownloadQueryCountSliceTest {

    @Test
    fun aBatchOfOneAndOfSixtyIssueTheSameStatements() {
        val (userId, scopeId) = seedMemberScope()
        val blobs = blobsOn(scopeId, seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY), 60)
        presign(userId, scopeId, blobs.take(1), "store_unavailable")
        assertEquals(
            CoreStatementCount.during { presign(userId, scopeId, blobs.take(1), "store_unavailable") },
            CoreStatementCount.during { presign(userId, scopeId, blobs, "store_unavailable") },
            "a space with no block",
        )
    }

    @Test
    fun underAContentBlockABatchOfOneAndOfSixtyIssueTheSameStatements() {
        val (userId, scopeId) = seedMemberScope()
        val blocked = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val blobs = blobsOn(scopeId, blocked, 60)
        val open = blobsOn(scopeId, seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY), 1)
        blockNode(scopeId, blocked)
        presign(userId, scopeId, open, "store_unavailable") // control: the block reaches only the node it names
        assertEquals(
            CoreStatementCount.during { presign(userId, scopeId, blobs.take(1), "blocked_by_operator") },
            CoreStatementCount.during { presign(userId, scopeId, blobs, "blocked_by_operator") },
            "a space with a content block",
        )
    }

    /** [n] blobs attached to [nodeId], each in a slot of its own (a node holds one attachment per slot). */
    private fun blobsOn(scopeId: String, nodeId: String, n: Int): List<String> =
        List(n) { seedAttachment(scopeId, nodeId, "CORE_TEST_$it").blobId }

    /** Presign [blobIds] in one batch; every item must answer [error] (none presigns in `%test`). */
    private fun presign(userId: String, scopeId: String, blobIds: List<String>, error: String) {
        val errors = given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body(blobIds.joinToString(",", """{"items":[""", "]}") { """{"blobId":"$it"}""" })
            .`when`().post("/api/scopes/$scopeId/blob-downloads-batch")
            .then().statusCode(200)
            .extract().path<List<String>>("results.error")
        assertEquals(List(blobIds.size) { error }, errors)
    }

    private fun blockNode(scopeId: String, nodeId: String) {
        QuarkusTransaction.requiringNew().run {
            ModerationBlockEntity().apply {
                id = UUID.randomUUID().toString(); kind = BlockKind.CONTENT_NODE.name; targetId = nodeId; this.scopeId = scopeId
                takenBy = UUID.randomUUID().toString(); takenAt = Instant.now()
                activeKey = ModerationBlockEntity.activeKeyOf(BlockKind.CONTENT_NODE, nodeId, scopeId)
            }.persist()
        }
    }
}
