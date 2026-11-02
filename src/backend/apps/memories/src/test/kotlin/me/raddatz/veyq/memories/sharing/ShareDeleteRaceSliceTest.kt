package me.raddatz.veyq.memories.sharing

import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.whileUncommitted
import me.raddatz.veyq.core.testing.raceRounds
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import me.raddatz.veyq.core.sharing.ShareWriteProof
import me.raddatz.veyq.core.sharing.SharingStore
import me.raddatz.veyq.core.sharing.ShareKeys
import java.time.Instant

/**
 * A share write that read the link before its delete committed (ADR 0029 §7). Every write but a re-issue loads the
 * share under a row lock (`SharingStore.liveForUpdate`), so it waits for the delete and then finds no share. Read
 * without it, the write landed on the tombstone: an edit stored fresh ciphertext the delete had just cleared, and a
 * re-activation switched the deleted link back on. `@DynamicUpdate` does not close this one — the write's own
 * columns are exactly the ones the tombstone cleared.
 */
@QuarkusTest
class ShareDeleteRaceSliceTest {

    @Inject
    lateinit var sharing: SharingStore

    private val wrap = "d3JhcC1rLW1lbS11bmRlci1zaGFyZQ=="
    private val sealedName = "c2VhbGVkLW5hbWU="
    private val note = "bm90ZQ==" // "note"

    @Test
    fun anEditOverlappingTheDeleteFindsNoShareAndWritesNothing() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        raceRounds { round ->
            // A fresh Memory a round: the delete marks the one below it due (ADR 0114 decision 1).
            val shareId = createShare(userId, seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY))
            val outcome = whileUncommitted(
                change = { assertTrue(sharing.delete(shareId), "round $round") },
                overlapping = { sharing.updateShare(userId, shareId, note, null, null, null, null, null, null, null, null, Instant.now()) },
            )
            assertEquals(SharingStore.UpdateResult.NOT_FOUND, outcome, "round $round")
            QuarkusTransaction.requiringNew().run {
                val row = ShareEntity.findById(shareId)!!
                assertTrue(row.deleted, "round $round")
                assertNull(row.sealedNote, "round $round: the edit wrote its ciphertext onto the tombstone")
            }
        }
        // Control: the same edit on a link nobody deletes lands.
        val kept = createShare(userId, memory)
        assertEquals(SharingStore.UpdateResult.DONE, sharing.updateShare(userId, kept, note, null, null, null, null, null, null, null, null, Instant.now()))
        assertEquals(note, QuarkusTransaction.requiringNew().call { ShareEntity.findById(kept)!!.sealedNote })
    }

    @Test
    fun aReactivationOverlappingTheDeleteLeavesTheLinkOff() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        raceRounds { round ->
            // A fresh Memory a round: the delete marks the one below it due (ADR 0114 decision 1).
            val shareId = createShare(userId, seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY))
            assertTrue(sharing.revoke(userId, shareId), "round $round")
            val outcome = whileUncommitted(
                change = { assertTrue(sharing.delete(shareId), "round $round") },
                overlapping = { sharing.reactivate(userId, shareId, reopen(userId), Instant.now()) },
            )
            assertFalse(outcome, "round $round")
            QuarkusTransaction.requiringNew().run {
                val row = ShareEntity.findById(shareId)!!
                assertTrue(row.deleted, "round $round")
                assertFalse(row.enabled, "round $round: the re-activation switched the deleted link back on")
            }
        }
        // Control: a revoked link nobody deletes re-activates.
        val kept = createShare(userId, memory)
        assertTrue(sharing.revoke(userId, kept))
        assertTrue(sharing.reactivate(userId, kept, reopen(userId), Instant.now()))
        assertTrue(QuarkusTransaction.requiringNew().call { ShareEntity.findById(kept)!!.enabled })
    }

    /** What a re-activation stores back (ADR 0114 decision 7) — the store takes it as the handler judged it. */
    private fun reopen(userId: String) = SharingStore.Reopen(ShareKeys.MemoryWrap(wrap), sealedName, userId)

    private fun createShare(userId: String, nodeId: String): String {
        val shareId = TestIds.shareId()
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"shareId":"$shareId",${ShareWriteProof.field(shareId)},"nodeId":"$nodeId","role":"READ","wrap":"$wrap","sealedName":"$sealedName",${ShareWriteProof.recovery()}}""")
            .`when`().post("/api/shares").then().statusCode(201)
        return shareId
    }
}
