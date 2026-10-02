package com.veyq.app.features.media

import com.veyq.app.features.content.NodeEntity
import com.veyq.app.shared.TestIds
import com.veyq.app.testing.RACE_ROUNDS
import com.veyq.app.testing.TestFixtures.CONTENT_KEY
import com.veyq.app.testing.TestFixtures.bearer
import com.veyq.app.testing.TestFixtures.seedMemberScope
import com.veyq.app.testing.TestFixtures.seedNode
import com.veyq.app.testing.answer
import com.veyq.app.testing.assertWinnerAndLoser
import com.veyq.app.testing.race
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The media creates raced against themselves (developer-styleguide §12): a double-submitted request must give its
 * loser the answer a replay gets — per item on the two batch endpoints — never a 500, and never one that hides the
 * items an earlier part of the batch already committed. Each test ends with a fresh create that succeeds, so a site
 * that refuses everything cannot pass.
 */
@QuarkusTest
class MediaCreateRaceSliceTest {

    /** Two completes of one upload: the sessions are spent first, so the loser's item is `upload_not_found`. */
    @Test
    fun concurrentCompletesOfOneUploadAttachItOnce() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY, wrappedContentKey = CONTENT_KEY)
        repeat(RACE_ROUNDS) { round ->
            val upload = startOne(userId, scopeId)
            val body = completeBody(memory, upload)
            val answers = race({ complete(userId, scopeId, body) }, { complete(userId, scopeId, body) })
            assertSameAnswers(listOf(200 to listOf(null), 200 to listOf("upload_not_found")), answers, "round $round")
            assertEquals(1L, mediaRows(upload.mediaId), "round $round: one media, got $answers")
        }
        val control = startOne(userId, scopeId)
        assertEquals(200 to listOf<String?>(null), complete(userId, scopeId, completeBody(memory, control)))
    }

    /**
     * The race lost by a batch's SECOND item: the batch still answers, with its first item attached and the lost one
     * `upload_not_found` — not a 500 that hides the first item, which a retry would then report as not found.
     */
    @Test
    fun aBatchWhoseSecondItemLosesTheRaceStillAnswersItsFirstItem() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY, wrappedContentKey = CONTENT_KEY)
        repeat(RACE_ROUNDS) { round ->
            val (a, c, shared) = List(3) { startOne(userId, scopeId) }
            val answers = race(
                { complete(userId, scopeId, completeBody(memory, a, shared)) },
                { complete(userId, scopeId, completeBody(memory, c, shared)) },
            )
            assertEquals(listOf(200, 200), answers.map { it.first }, "round $round: both batches answer, got $answers")
            assertEquals(listOf<String?>(null, null), answers.map { it.second.first() }, "round $round: first items, got $answers")
            assertEquals(
                setOf(null, "upload_not_found"),
                answers.map { it.second[1] }.toSet(),
                "round $round: the shared item attaches once, got $answers",
            )
            listOf(a, c, shared).forEach { assertEquals(1L, mediaRows(it.mediaId), "round $round: ${it.mediaId}") }
        }
    }

    /**
     * Two commits of one cover upload: the session is spent first, as `attach` spends its own, so the loser answers
     * what its replay — the same commit again, after both returned — answers: the upload is gone, a `404`. One
     * Memory across the rounds, so from the second round on each commit also replaces the previous round's cover.
     */
    @Test
    fun concurrentCoverCommitsOfOneUploadAttachItOnce() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY, wrappedContentKey = CONTENT_KEY)
        repeat(RACE_ROUNDS) { round ->
            val uploadId = startCover(userId, scopeId, memory)
            val answers = race({ commitCover(userId, scopeId, memory, uploadId) }, { commitCover(userId, scopeId, memory, uploadId) })
            val replay = commitCover(userId, scopeId, memory, uploadId)
            assertEquals(404 to "node_not_found", replay, "round $round: the replay's answer")
            assertWinnerAndLoser(200, replay, answers, "round $round")
        }
        assertEquals(200 to null, commitCover(userId, scopeId, memory, startCover(userId, scopeId, memory)))
    }

    /** Two creates of one sub-bucket id: the loser answers `id_taken`, as a replay does. */
    @Test
    fun concurrentSubBucketCreatesOfOneIdCreateItOnce() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY, wrappedContentKey = CONTENT_KEY)
        // The caller's primary bucket exists before the race, so only the sub-bucket insert is raced.
        assertEquals(200 to null, createBucket(userId, scopeId, memory, TestIds.uuid()))
        repeat(RACE_ROUNDS) { round ->
            val bucketId = TestIds.uuid()
            val answers = race({ createBucket(userId, scopeId, memory, bucketId) }, { createBucket(userId, scopeId, memory, bucketId) })
            assertWinnerAndLoser(200, 409 to "id_taken", answers, "round $round")
        }
        assertEquals(200 to null, createBucket(userId, scopeId, memory, TestIds.uuid()))
    }

    /**
     * Two starts naming one client-minted blob id: one session opens, the other item answers `id_taken`. Green before
     * this site was wrapped: both starts lock the one tenant's row before they check the id, so they never overlap.
     * It runs ten rounds for that reason; the race the lock does not order is the next test's.
     */
    @Test
    fun concurrentStartsOfOneBlobIdOpenOneSession() {
        val (userId, scopeId) = seedMemberScope()
        repeat(2 * RACE_ROUNDS) { round ->
            val item = TestBlob.SMALL.declare("d3JhcA==")
            val answers = race({ start(userId, scopeId, item) }, { start(userId, scopeId, item) })
            assertSameAnswers(listOf(200 to listOf(null), 200 to listOf("id_taken")), answers, "round $round")
        }
        assertEquals(200 to listOf<String?>(null), start(userId, scopeId, TestBlob.SMALL.declare("d3JhcA==")))
    }

    /**
     * The same race across two tenants: a start locks only its own tenant's row, so two starts into scopes of
     * different tenants are not ordered by it — and a blob id is unique everywhere (ADR 0072).
     */
    @Test
    fun concurrentStartsOfOneBlobIdInTwoTenantsOpenOneSession() {
        val (userId, scopeA) = seedMemberScope()
        val scopeB = seedMemberScope(userId = userId).scopeId
        repeat(RACE_ROUNDS) { round ->
            val item = TestBlob.SMALL.declare("d3JhcA==")
            val answers = race({ start(userId, scopeA, item) }, { start(userId, scopeB, item) })
            assertSameAnswers(listOf(200 to listOf(null), 200 to listOf("id_taken")), answers, "round $round")
        }
        assertEquals(200 to listOf<String?>(null), start(userId, scopeB, TestBlob.SMALL.declare("d3JhcA==")))
    }

    /** The two answers of a race, in either order. */
    private fun assertSameAnswers(expected: List<Pair<Int, List<String?>>>, answers: List<Pair<Int, List<String?>>>, round: String) =
        assertEquals(expected.sortedBy { it.toString() }, answers.sortedBy { it.toString() }, round)

    private data class Upload(val uploadId: String, val mediaId: String)

    private fun startOne(userId: String, scopeId: String): Upload {
        val mediaId = UUID.randomUUID().toString()
        val uploadId = given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[${TestBlob.SMALL.declare("d3JhcA==", ownerId = mediaId)}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch")
            .then().statusCode(200).extract().path<String>("results[0].uploadId")
        return Upload(uploadId, mediaId)
    }

    private fun completeBody(nodeId: String, vararg uploads: Upload) =
        uploads.joinToString(",", """{"items":[""", "]}") {
            """{"mediaId":"${it.mediaId}","originalUploadId":"${it.uploadId}","nodeId":"$nodeId"}"""
        }

    /** A batch's answer: its status and each item's error (empty when the batch did not answer per item). */
    private fun complete(userId: String, scopeId: String, body: String): Pair<Int, List<String?>> =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON).body(body)
            .`when`().post("/api/scopes/$scopeId/media-batch").then().extract()
            .let { r -> r.statusCode() to (runCatching { r.path<List<String?>>("results.error") }.getOrNull() ?: emptyList()) }

    private fun start(userId: String, scopeId: String, item: String): Pair<Int, List<String?>> =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON).body("""{"items":[$item]}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch").then().extract()
            .let { r -> r.statusCode() to (runCatching { r.path<List<String?>>("results.error") }.getOrNull() ?: emptyList()) }

    private fun startCover(userId: String, scopeId: String, nodeId: String): String =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[${TestBlob.SMALL.declare("d3JhcA==", ownerId = nodeId, role = "COVER")}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch")
            .then().statusCode(200).extract().path("results[0].uploadId")

    private fun commitCover(userId: String, scopeId: String, nodeId: String, uploadId: String): Pair<Int, String?> =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON).body("""{"uploadId":"$uploadId"}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$nodeId/cover-blob").then().extract().answer()

    private fun createBucket(userId: String, scopeId: String, nodeId: String, bucketId: String): Pair<Int, String?> =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"bucketId":"$bucketId","sealedName":"YnVja2V0"}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$nodeId/buckets").then().extract().answer()

    private fun mediaRows(mediaId: String): Long = QuarkusTransaction.requiringNew().call { MediaEntity.count("id", mediaId) }
}
