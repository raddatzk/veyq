package me.raddatz.veyq.core.content

import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.persistence.createOrConflict
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.placementTo
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.util.Base64
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The principal-free node operations confined to a [SubtreeBoundary] (5a.2 of cr-node-key-hierarchy; ADR 0080
 * Decisions 6, 9, 10): the node acted on strictly inside, a parent or destination inside (the root included), the
 * boundary's root never moved, renamed or deleted. No endpoint passes a boundary yet — the share guest operations will
 * — so the operations are driven directly. Every refusal writes nothing; the races are `SubtreeBoundaryRaceCoreSliceTest`.
 */
@QuarkusTest
class SubtreeBoundaryCoreSliceTest {

    @Inject lateinit var handler: NodesHandler

    @Test
    fun aPlacementInsideTheBoundaryLandsAndOneReachingOutIsRefused() {
        val t = tree()
        // the node outside: not found, as a foreign node
        assertEquals(UpdateNodeOutcome.NotFound, place(t, t.outside, to = t.a))
        assertEquals(t.scopeId, parentOf(t.outside))
        // the destination outside: a sibling of the root, and the scope root itself
        assertEquals(UpdateNodeOutcome.IllegalStructure, place(t, t.x, to = t.outside))
        assertEquals(UpdateNodeOutcome.IllegalStructure, place(t, t.x, to = null))
        assertEquals(t.root, parentOf(t.x))
        // the boundary's root is never placed — not even renamed where it is
        assertEquals(UpdateNodeOutcome.IllegalStructure, place(t, t.root, to = null))
        assertEquals(0L, versionOf(t.root))

        // inside: into a folder of the subtree, and back to the root of the boundary
        assertEquals(UpdateNodeOutcome.Updated, place(t, t.x, to = t.a))
        assertEquals(t.a, parentOf(t.x))
        assertEquals(UpdateNodeOutcome.Updated, place(t, t.x, to = t.root, version = 1))
        assertEquals(t.root, parentOf(t.x))
    }

    @Test
    fun aCreateLandsOnlyUnderTheBoundary() {
        val t = tree()
        assertEquals(CreateNodeOutcome.IllegalStructure, create(t, parent = t.outside).second)
        assertEquals(CreateNodeOutcome.IllegalStructure, create(t, parent = null).second)
        val (inRoot, atRoot) = create(t, parent = t.root)
        val (inA, underA) = create(t, parent = t.a)
        assertEquals(CreateNodeOutcome.Created(inRoot), atRoot)
        assertEquals(CreateNodeOutcome.Created(inA), underA)
    }

    @Test
    fun aDeleteReachesOnlyStrictlyInside() {
        val t = tree()
        assertEquals(DeleteNodeOutcome.NoScope, handler.deleteInScope(t.scopeId, t.root, SubtreeBoundary(t.root)))
        assertEquals(DeleteNodeOutcome.NoScope, handler.deleteInScope(t.scopeId, t.outside, SubtreeBoundary(t.root)))
        assertNull(deletedAt(t.root))
        assertNull(deletedAt(t.outside))

        assertEquals(DeleteNodeOutcome.Deleted, handler.deleteInScope(t.scopeId, t.a, SubtreeBoundary(t.root)))
        assertNotNull(deletedAt(t.a))
    }

    // --- helpers ---

    /** root and outside at the scope root; a and x under root. The boundary is root's subtree. */
    private data class Tree(val scopeId: String, val root: String, val a: String, val x: String, val outside: String)

    private fun tree(): Tree {
        val (_, scopeId) = seedMemberScope()
        val root = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val a = seedNode(scopeId, NodeEntity.FOLDER, parentId = root, wrappedContentKey = CONTENT_KEY)
        val x = seedNode(scopeId, NodeEntity.FOLDER, parentId = root, wrappedContentKey = CONTENT_KEY)
        val outside = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        return Tree(scopeId, root, a, x, outside)
    }

    private fun place(t: Tree, node: String, to: String?, version: Long = 0): UpdateNodeOutcome =
        handler.updateInScope(t.scopeId, node, placementTo(to, placementVersion = version), null, null, null, SubtreeBoundary(t.root))

    private fun create(t: Tree, parent: String?): Pair<String, CreateNodeOutcome> {
        val id = UUID.randomUUID().toString()
        val slug = Base64.getEncoder().encodeToString(id.toByteArray())
        // createInScope joins its caller's transaction; the caller opens it, as the member's create does.
        return id to createOrConflict(conflict = handler.createReplay(t.scopeId, parent, slug, id)) {
            handler.createInScope(t.scopeId, id, parent, NodeEntity.FOLDER, "bmV3", slug, 0, 0, CONTENT_KEY, null, SubtreeBoundary(t.root))
        }
    }

    private fun parentOf(node: String): String = QuarkusTransaction.requiringNew().call { NodeEntity.findById(node)!!.parentId }

    private fun versionOf(node: String): Long = QuarkusTransaction.requiringNew().call { NodeEntity.findById(node)!!.placementVersion }

    private fun deletedAt(node: String) = QuarkusTransaction.requiringNew().call { NodeEntity.findById(node)!!.deletedAt }
}
