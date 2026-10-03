package com.veyq.app.features.sharing

import com.veyq.app.core.node.NodeEntity
import com.veyq.app.core.sharing.ShareEntity
import com.veyq.app.shared.TestIds
import com.veyq.app.testing.RACE_ROUNDS
import com.veyq.app.testing.TestFixtures.CONTENT_KEY
import com.veyq.app.testing.TestFixtures.bearer
import com.veyq.app.testing.TestFixtures.seedMemberScope
import com.veyq.app.testing.TestFixtures.seedNode
import com.veyq.app.testing.whileUncommitted
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
        val memory = seedNode(scopeId, NodeEntity.MEMORY, wrappedContentKey = CONTENT_KEY)
        repeat(RACE_ROUNDS) { round ->
            val shareId = createShare(userId, memory)
            val outcome = whileUncommitted(
                change = { assertTrue(sharing.delete(shareId), "round $round") },
                overlapping = { sharing.updateShare(userId, shareId, note, null, null, null, null, null, null, null) },
            )
            assertEquals(SharingStore.UpdateResult.NOT_FOUND, outcome, "round $round")
            QuarkusTransaction.requiringNew().run {
                val row = ShareEntity.findById(shareId)!!
                assertNotNull(row.deletedAt, "round $round")
                assertNull(row.sealedNote, "round $round: the edit wrote its ciphertext onto the tombstone")
            }
        }
        // Control: the same edit on a link nobody deletes lands.
        val kept = createShare(userId, memory)
        assertEquals(SharingStore.UpdateResult.DONE, sharing.updateShare(userId, kept, note, null, null, null, null, null, null, null))
        assertEquals(note, QuarkusTransaction.requiringNew().call { ShareEntity.findById(kept)!!.sealedNote })
    }

    @Test
    fun aReactivationOverlappingTheDeleteLeavesTheLinkOff() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY, wrappedContentKey = CONTENT_KEY)
        repeat(RACE_ROUNDS) { round ->
            val shareId = createShare(userId, memory)
            assertTrue(sharing.revoke(userId, shareId), "round $round")
            val outcome = whileUncommitted(
                change = { assertTrue(sharing.delete(shareId), "round $round") },
                overlapping = { sharing.reactivate(userId, shareId) },
            )
            assertFalse(outcome, "round $round")
            QuarkusTransaction.requiringNew().run {
                val row = ShareEntity.findById(shareId)!!
                assertNotNull(row.deletedAt, "round $round")
                assertFalse(row.enabled, "round $round: the re-activation switched the deleted link back on")
            }
        }
        // Control: a revoked link nobody deletes re-activates.
        val kept = createShare(userId, memory)
        assertTrue(sharing.revoke(userId, kept))
        assertTrue(sharing.reactivate(userId, kept))
        assertTrue(QuarkusTransaction.requiringNew().call { ShareEntity.findById(kept)!!.enabled })
    }

    private fun createShare(userId: String, nodeId: String): String {
        val shareId = TestIds.shareId()
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"shareId":"$shareId",${ShareWriteProof.field(shareId)},"nodeId":"$nodeId","role":"VIEWER","wrap":"$wrap","sealedName":"$sealedName"}""")
            .`when`().post("/api/shares").then().statusCode(201)
        return shareId
    }
}
