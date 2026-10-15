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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Two placement writes of one tree overlap (ADR 0080 Decision 3). Each race holds the first writer's commit back
 * ([whileUncommitted]) and runs the second inside that window, so the second reads the tree as it was and decides
 * its fast-path checks on it; only the scope lock and the compare-and-set under it stand between it and a lost
 * update or a cycle. Each race has a control: the loser, rebuilt on what it re-reads, lands.
 *
 *  - a rename against a move of the same node: exactly one lands, and the stored name, slug and wrap are all the
 *    winner's — never the loser's name on the winner's parent (it was sealed under another key);
 *  - two moves that would form a cycle together (a into b while b into a), each legal alone: one lands, the other is
 *    `illegal_structure`, and no cycle is stored — the CAS cannot catch this (the two writes touch different rows),
 *    the scope lock and the cycle check under it do;
 *  - two moves of one node to different parents: exactly one lands.
 */
@QuarkusTest
class NodePlacementRaceCoreSliceTest {

    @Inject lateinit var handler: NodesHandler

    @Test
    fun aRenameOverlappingAMoveOfTheSameNodeLosesWhole() {
        repeat(RACE_ROUNDS) { round ->
            val t = tree()
            val move = placementTo(t.b, encryptedName = "bW92ZWQ=", wrappedContentKey = "bW92ZWQtd3JhcA==")
            val rename = placementTo(t.a, encryptedName = "cmVuYW1lZA==", wrappedContentKey = "cmVuYW1lZC13cmFw")
            val loser = whileUncommitted(
                change = { assertEquals(UpdateNodeOutcome.Updated, update(t, t.child, move), "round $round: the move") },
                overlapping = { update(t, t.child, rename) },
            )
            assertEquals(UpdateNodeOutcome.StalePlacement, loser, "round $round: the rename built on version 0")
            assertEquals(Row(t.b, "bW92ZWQ=", move.slugToken!!, "bW92ZWQtd3JhcA==", 1), row(t.child), "round $round")

            // control: the rename rebuilt on what it re-reads (version 1, the new parent) lands
            assertEquals(UpdateNodeOutcome.Updated, update(t, t.child, rename.copy(parentId = t.b, placementRevision = 1)), "round $round")
            assertEquals(Row(t.b, "cmVuYW1lZA==", rename.slugToken!!, "cmVuYW1lZC13cmFw", 2), row(t.child), "round $round")
        }
    }

    @Test
    fun twoMovesThatWouldFormACycleTogetherLandOneOfThem() {
        repeat(RACE_ROUNDS) { round ->
            val t = tree()
            val loser = whileUncommitted(
                change = { assertEquals(UpdateNodeOutcome.Updated, update(t, t.a, placementTo(t.b)), "round $round: a into b") },
                overlapping = { update(t, t.b, placementTo(t.a)) },
            )
            assertEquals(UpdateNodeOutcome.IllegalStructure, loser, "round $round: b into a, after a went into b")
            assertEquals(t.b, row(t.a).parentId, "round $round")
            assertEquals(Row(t.scopeId, "Yg==", row(t.b).slugToken, CONTENT_KEY, 0), row(t.b), "round $round: b unchanged")
        }
        // control: alone, b into a lands
        val t = tree()
        assertEquals(UpdateNodeOutcome.Updated, update(t, t.b, placementTo(t.a)))
    }

    @Test
    fun twoMovesOfOneNodeToDifferentParentsLandOne() {
        repeat(RACE_ROUNDS) { round ->
            val t = tree()
            val toB = placementTo(t.b, encryptedName = "dG8tYg==", wrappedContentKey = "dG8tYi13cmFw")
            val toRoot = placementTo(null, encryptedName = "dG8tcm9vdA==", wrappedContentKey = "dG8tcm9vdC13cmFw")
            val loser = whileUncommitted(
                change = { assertEquals(UpdateNodeOutcome.Updated, update(t, t.child, toB), "round $round: to b") },
                overlapping = { update(t, t.child, toRoot) },
            )
            assertEquals(UpdateNodeOutcome.StalePlacement, loser, "round $round: to the root, built on version 0")
            assertEquals(Row(t.b, "dG8tYg==", toB.slugToken!!, "dG8tYi13cmFw", 1), row(t.child), "round $round")
        }
    }

    // --- helpers ---

    private data class Tree(val userId: String, val scopeId: String, val a: String, val b: String, val child: String)

    private data class Row(val parentId: String, val encryptedName: String, val slugToken: String, val wrap: String, val version: Long)

    private fun tree(): Tree {
        val (userId, scopeId) = seedMemberScope()
        val a = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY, encryptedName = "YQ==")
        val b = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY, encryptedName = "Yg==")
        val child = seedNode(scopeId, NodeEntity.FOLDER, parentId = a, wrappedContentKey = CONTENT_KEY, encryptedName = "Yw==")
        return Tree(userId, scopeId, a, b, child)
    }

    private fun update(t: Tree, node: String, placement: NodesHandler.PlacementRequest): UpdateNodeOutcome =
        handler.update(t.userId, t.scopeId, node, placement, null, null, null)

    private fun row(node: String): Row = QuarkusTransaction.requiringNew().call {
        NodeEntity.findById(node)!!.let { Row(it.parentId, it.encryptedName, it.slugToken, it.wrappedContentKey, it.placementRevision) }
    }
}
