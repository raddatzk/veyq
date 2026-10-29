package me.raddatz.veyq.core.blob

import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.testing.RACE_ROUNDS
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.answer
import me.raddatz.veyq.core.testing.race
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The media creates raced against themselves (developer-styleguide §12): a double-submitted request must give its
 * loser the answer a replay gets — per item on the two batch endpoints — never a 500, and never one that hides the
 * items an earlier part of the batch already committed. Each test ends with a fresh create that succeeds, so a site
 * that refuses everything cannot pass.
 *
 * **Split from `MediaCreateRaceSliceTest`** (`src/backend/CLAUDE.md`: a test lives where what it exercises runs): these methods run with core alone, so they live in
 * `:core`; the rest of the class is `MediaCreateRaceSliceTest` in `:apps:memories`. The bodies moved verbatim.
 */
@QuarkusTest
class MediaCreateRaceCoreSliceTest {

    private data class Upload(val uploadId: String, val mediaId: String)

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

    private fun start(userId: String, scopeId: String, item: String): Pair<Int, List<String?>> =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON).body("""{"items":[$item]}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch").then().extract()
            .let { r -> r.statusCode() to (runCatching { r.path<List<String?>>("results.error") }.getOrNull() ?: emptyList()) }
}
