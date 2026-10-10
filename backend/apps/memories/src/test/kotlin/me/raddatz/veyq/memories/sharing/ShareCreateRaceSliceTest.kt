package me.raddatz.veyq.memories.sharing

import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.testing.RACE_ROUNDS
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.DROP_PRIVATE_KEY
import me.raddatz.veyq.core.testing.TestFixtures.DROP_PUBLIC_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.answer
import me.raddatz.veyq.core.testing.assertWinnerAndLoser
import me.raddatz.veyq.core.testing.race
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import me.raddatz.veyq.core.sharing.ShareWriteProof

/**
 * The sharing creates raced against themselves (developer-styleguide §12): a double-submitted share or guest identity
 * create must give its loser `id_taken` — what a replay of it gets — never the 500 of the primary key it collided
 * with. Each test ends with a fresh create that succeeds, so a site that refuses everything cannot pass.
 */
@QuarkusTest
class ShareCreateRaceSliceTest {

    private val wrap = "d3JhcC1rLW1lbS11bmRlci1zaGFyZQ=="
    private val sealedName = "c2VhbGVkLW5hbWU="

    @Test
    fun concurrentShareCreatesOfOneIdCreateItOnce() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        repeat(RACE_ROUNDS) { round ->
            val shareId = TestIds.shareId()
            val answers = race({ createShare(userId, memory, shareId) }, { createShare(userId, memory, shareId) })
            assertWinnerAndLoser(201, 409 to "id_taken", answers, "round $round")
            assertEquals(1L, QuarkusTransaction.requiringNew().call { ShareEntity.count("id", shareId) }, "round $round")
        }
        assertEquals(201 to null, createShare(userId, memory, TestIds.shareId()))
    }

    /**
     * The first WRITE_ONLY link of a Memory, double-submitted: both requests carry the drop private key, so both reach
     * the guarded drop-key update, and the loser's update waits for the winner's and then finds the key set. Its
     * answer is named by running its replay — the same body again, after both committed — which meets the share id
     * check first and answers `id_taken`. `drop_key_exists` would send the web client to re-read the node and retry
     * the one id the winner already holds. A fresh Memory each round: the race is over its first drop key.
     */
    @Test
    fun concurrentUploaderShareCreatesOfOneIdAnswerAsTheirReplay() {
        val (userId, scopeId) = seedMemberScope()
        repeat(RACE_ROUNDS) { round ->
            val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
            val shareId = TestIds.shareId()
            val answers = race({ createUploaderShare(userId, memory, shareId) }, { createUploaderShare(userId, memory, shareId) })
            val replay = createUploaderShare(userId, memory, shareId)
            assertEquals(409 to "id_taken", replay, "round $round: the replay's answer")
            assertWinnerAndLoser(201, replay, answers, "round $round")
        }
        assertEquals(201 to null, createUploaderShare(userId, seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY), TestIds.shareId()))
    }

    /**
     * The control for the test above: two DIFFERENT WRITE_ONLY links racing for one Memory's first drop key. Their ids
     * are both free, so the loser's replay is refused by the drop-key check, and `drop_key_exists` stays its answer —
     * the one the web client answers by sealing to the winner's key.
     */
    @Test
    fun concurrentUploaderSharesOfTwoIdsKeepOneDropKey() {
        val (userId, scopeId) = seedMemberScope()
        repeat(RACE_ROUNDS) { round ->
            val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
            val (first, second) = TestIds.shareId() to TestIds.shareId()
            val answers = race({ createUploaderShare(userId, memory, first) }, { createUploaderShare(userId, memory, second) })
            val loser = if (answers[0].first == 201) second else first
            val replay = createUploaderShare(userId, memory, loser)
            assertEquals(409 to "drop_key_exists", replay, "round $round: the replay's answer")
            assertWinnerAndLoser(201, replay, answers, "round $round")
        }
    }

    /** A fresh READ_WRITE link each round: identity creation is rate-limited per link. */
    @Test
    fun concurrentIdentityCreatesOfOneIdCreateItOnce() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        repeat(RACE_ROUNDS) { round ->
            val shareId = TestIds.shareId()
            assertEquals(201 to null, createShare(userId, memory, shareId, role = "READ_WRITE"))
            val identityId = TestIds.uuid()
            val answers = race({ createIdentity(shareId, identityId) }, { createIdentity(shareId, identityId) })
            assertWinnerAndLoser(201, 409 to "id_taken", answers, "round $round")
        }
        val shareId = TestIds.shareId()
        assertEquals(201 to null, createShare(userId, memory, shareId, role = "READ_WRITE"))
        assertEquals(201 to null, createIdentity(shareId, TestIds.uuid()))
    }

    private fun createShare(userId: String, nodeId: String, shareId: String, role: String = "READ"): Pair<Int, String?> =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"shareId":"$shareId",${ShareWriteProof.field(shareId)},"nodeId":"$nodeId","role":"$role","wrap":"$wrap","sealedName":"$sealedName",${ShareWriteProof.recovery()}}""")
            .`when`().post("/api/shares").then().extract().answer()

    private fun createUploaderShare(userId: String, nodeId: String, shareId: String): Pair<Int, String?> =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body(
                """{"shareId":"$shareId",${ShareWriteProof.field(shareId)},"nodeId":"$nodeId","role":"WRITE_ONLY",""" +
                    """"sealedDropPublicKey":"$DROP_PUBLIC_KEY","sealedDropPrivateKey":"$DROP_PRIVATE_KEY","sealedName":"$sealedName",${ShareWriteProof.recovery()}}""",
            )
            .`when`().post("/api/shares").then().extract().answer()

    private fun createIdentity(shareId: String, identityId: String): Pair<Int, String?> =
        given().contentType(ContentType.JSON)
            .body("""{"identityId":"$identityId","sealedDisplayName":"c2VhbGVkLWNvbnRyaWJ1dG9yLW5hbWU="}""")
            .header(ShareWriteProof.HEADER, ShareWriteProof.token(shareId))
            .`when`().post("/api/shares/$shareId/identities").then().extract().answer()
}
