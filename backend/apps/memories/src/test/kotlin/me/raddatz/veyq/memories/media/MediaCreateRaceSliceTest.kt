package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.identity.PrincipalKey
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.testing.RACE_ROUNDS
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.answer
import me.raddatz.veyq.core.testing.CoverKit
import me.raddatz.veyq.core.testing.assertWinnerAndLoser
import me.raddatz.veyq.core.testing.race
import me.raddatz.veyq.core.testing.whileUncommitted
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * The media creates raced against themselves (developer-styleguide §12): a double-submitted request must give its
 * loser the answer a replay gets — per item on the two batch endpoints — never a 500, and never one that hides the
 * items an earlier part of the batch already committed. Each test ends with a fresh create that succeeds, so a site
 * that refuses everything cannot pass.
 *
 * **Split** (cr-platform-gradle-modules step 8a): the methods that run with core alone are `MediaCreateRaceCoreSliceTest` in `:core`.
 */
@QuarkusTest
class MediaCreateRaceSliceTest {

    @Inject lateinit var store: MediaStore

    /** Two completes of one upload: the sessions are spent first, so the loser's item is `upload_not_found`. */
    @Test
    fun concurrentCompletesOfOneUploadAttachItOnce() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
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
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
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
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        repeat(RACE_ROUNDS) { round ->
            val uploadId = startCover(userId, scopeId, memory)
            // both commits state the slot and the meta revision they read before the race: one wins, one meets a spent session
            val expected = CoverKit.slot(memory)
            val version = CoverKit.meta(memory).second
            val answers = race(
                { commitCover(userId, scopeId, memory, uploadId, expected, version) },
                { commitCover(userId, scopeId, memory, uploadId, expected, version) },
            )
            val replay = commitCover(userId, scopeId, memory, uploadId, expected, version)
            assertEquals(404 to "node_not_found", replay, "round $round: the replay's answer")
            assertWinnerAndLoser(204, replay, answers, "round $round")
        }
        assertEquals(204 to null, commitCover(userId, scopeId, memory, startCover(userId, scopeId, memory), CoverKit.slot(memory), CoverKit.meta(memory).second))
    }

    /** Two creates of one sub-bucket id: the loser answers `id_taken`, as a replay does. */
    @Test
    fun concurrentSubBucketCreatesOfOneIdCreateItOnce() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
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
     * Two first uploads of one uploader into one Memory: each resolves the uploader's missing primary bucket. Read
     * without the node row's lock, both found none and both created one — the uploader listed twice in the "Beiträge"
     * tab, their media split across the two (ADR 0034 §4). Under it, the second waits for the first and lands in its
     * primary. Forced, not likely: the first attach is held uncommitted ([whileUncommitted]) while the second runs.
     */
    @Test
    fun concurrentFirstUploadsOfOneUploaderShareOnePrimary() {
        repeat(RACE_ROUNDS) { round ->
            val (userId, scopeId) = seedMemberScope()
            val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
            val (first, second) = startOne(userId, scopeId) to startOne(userId, scopeId)
            val answer = whileUncommitted(
                change = {
                    val session = UploadSessionEntity.findById(first.uploadId)!!
                    assertNotNull(store.attach(first.mediaId, OriginalAttach.Upload(session, null), memory, emptyList(), null, null, uploaderUserId = userId), "round $round")
                },
                overlapping = { complete(userId, scopeId, completeBody(memory, second)) },
            )
            assertEquals(200 to listOf<String?>(null), answer, "round $round: the second upload lands")
            QuarkusTransaction.requiringNew().run {
                val primaries = BucketEntity.list("nodeId = ?1 and ownerKey = ?2 and isPrimary = true", memory, PrincipalKey.User(userId).format())
                assertEquals(1, primaries.size, "round $round: one uploader, ${primaries.size} primary buckets")
                listOf(first, second).forEach {
                    assertEquals(primaries.single().id, MediaEntity.findById(it.mediaId)!!.bucketId, "round $round: ${it.mediaId}")
                }
            }
        }
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

    private fun startCover(userId: String, scopeId: String, nodeId: String): String =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"items":[${TestBlob.SMALL.declare("d3JhcA==", ownerId = nodeId, role = "COVER")}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch")
            .then().statusCode(200).extract().path("results[0].uploadId")

    private fun commitCover(userId: String, scopeId: String, nodeId: String, uploadId: String, expected: String?, version: Long): Pair<Int, String?> =
        CoverKit.set(userId, scopeId, nodeId, uploadId, expected, metaRevision = version).extract().answer()

    private fun createBucket(userId: String, scopeId: String, nodeId: String, bucketId: String): Pair<Int, String?> =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"bucketId":"$bucketId","sealedName":"YnVja2V0"}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$nodeId/buckets").then().extract().answer()

    private fun mediaRows(mediaId: String): Long = QuarkusTransaction.requiringNew().call { MediaEntity.count("id", mediaId) }
}
