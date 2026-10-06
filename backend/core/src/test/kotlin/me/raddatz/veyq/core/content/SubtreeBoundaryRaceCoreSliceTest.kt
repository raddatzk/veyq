package me.raddatz.veyq.core.content

import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.testing.RACE_ROUNDS
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.placementTo
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.whileUncommitted
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.util.Base64
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A [SubtreeBoundary] is decided under the scope lock (5a.2 of cr-node-key-hierarchy). Each race holds a member's move
 * of folder `a` out of the boundary uncommitted ([whileUncommitted]) and runs a bounded write targeting `a` inside
 * that window: its unlocked fast path still sees `a` inside the boundary, so only the walk under the scope lock stands
 * between it and a node landing outside. Exactly one outcome: the member's move lands, the bounded write is
 * `illegal_structure` and writes nothing. Each race has a control: alone, the bounded write lands.
 */
@QuarkusTest
class SubtreeBoundaryRaceCoreSliceTest {

    @Inject lateinit var handler: NodesHandler

    @Test
    fun aBoundedMoveIntoAFolderAMemberMovesOutMeanwhileIsRefused() {
        repeat(RACE_ROUNDS) { round ->
            val t = tree()
            val loser = whileUncommitted(
                change = { assertEquals(UpdateNodeOutcome.Updated, memberMovesAOut(t), "round $round: a out of the boundary") },
                overlapping = { boundedMove(t) },
            )
            assertEquals(UpdateNodeOutcome.IllegalStructure, loser, "round $round: x into a, after a left")
            assertEquals(t.outside, parentOf(t.a), "round $round")
            assertEquals(t.root, parentOf(t.x), "round $round: x stays inside")
        }
        val t = tree() // control
        assertEquals(UpdateNodeOutcome.Updated, boundedMove(t))
        assertEquals(t.a, parentOf(t.x))
    }

    @Test
    fun aBoundedCreateUnderAFolderAMemberMovesOutMeanwhileIsRefused() {
        repeat(RACE_ROUNDS) { round ->
            val t = tree()
            val id = UUID.randomUUID().toString()
            val loser = whileUncommitted(
                change = { assertEquals(UpdateNodeOutcome.Updated, memberMovesAOut(t), "round $round: a out of the boundary") },
                overlapping = { boundedCreate(t, id) },
            )
            assertEquals(CreateNodeOutcome.IllegalStructure, loser, "round $round: a create under a, after a left")
            assertNull(QuarkusTransaction.requiringNew().call { NodeEntity.findById(id) }, "round $round: nothing created")
        }
        val t = tree() // control
        val id = UUID.randomUUID().toString()
        assertEquals(CreateNodeOutcome.Created(id), boundedCreate(t, id))
    }

    // --- helpers ---

    /** root and outside at the scope root; a and x under root. The boundary is root's subtree. */
    private data class Tree(val userId: String, val scopeId: String, val root: String, val a: String, val x: String, val outside: String)

    private fun tree(): Tree {
        val (userId, scopeId) = seedMemberScope()
        val root = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val a = seedNode(scopeId, NodeEntity.FOLDER, parentId = root, wrappedContentKey = CONTENT_KEY)
        val x = seedNode(scopeId, NodeEntity.FOLDER, parentId = root, wrappedContentKey = CONTENT_KEY)
        val outside = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        return Tree(userId, scopeId, root, a, x, outside)
    }

    private fun memberMovesAOut(t: Tree): UpdateNodeOutcome =
        handler.update(t.userId, t.scopeId, t.a, placementTo(t.outside), null, null, null)

    private fun boundedMove(t: Tree): UpdateNodeOutcome =
        handler.updateInScope(t.scopeId, t.x, placementTo(t.a), null, null, null, SubtreeBoundary(t.root))

    private fun boundedCreate(t: Tree, id: String): CreateNodeOutcome =
        handler.createInScope(
            t.scopeId, id, t.a, NodeEntity.FOLDER, "bmV3", Base64.getEncoder().encodeToString(id.toByteArray()),
            0, 0, CONTENT_KEY, null, SubtreeBoundary(t.root),
        )

    private fun parentOf(node: String): String = QuarkusTransaction.requiringNew().call { NodeEntity.findById(node)!!.parentId }
}
