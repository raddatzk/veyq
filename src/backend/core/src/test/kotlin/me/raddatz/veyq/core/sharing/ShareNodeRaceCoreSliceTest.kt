package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.content.DeleteNodeOutcome
import me.raddatz.veyq.core.content.NodesHandler
import me.raddatz.veyq.core.content.UpdateNodeOutcome
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.placementTo
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.whileUncommitted
import me.raddatz.veyq.core.testing.holdForOverlap
import me.raddatz.veyq.core.testing.raceRounds
import io.quarkus.arc.Arc
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A guest's tree operation decides everything under the scope lock and then the share row's, in that order
 * (`ShareNodesHandler`'s lock order; sub-slice 5a.3 of cr-node-key-hierarchy). Each race holds a member's or a curator's
 * write uncommitted ([whileUncommitted]) and runs the guest's operation in that window, whose gate — read before any
 * lock — still sees the old state: only the decisions under the locks stand between it and a wrong write. Exactly one
 * outcome each time, and a control where the guest's operation lands alone.
 *
 *  1. A member's move of the destination out of the subtree: the guest's move into it is `illegal_structure`, nothing
 *     lands outside the share's subtree.
 *  2. A member's trash of the parent: the guest's create under it is `illegal_structure`, no child under a trashed
 *     parent.
 *  3. A curator's revoke or delete of the link: the guest's write is the link's 404 and writes nothing.
 *  4. A re-activation of the link holding the scope lock and then asking for the share row (its order, and a
 *     removal's): the guest's write waits on the scope and lands after it — taking the share row first, it would
 *     hold what the re-activation waits for while waiting for what the re-activation holds.
 */
@QuarkusTest
class ShareNodeRaceCoreSliceTest {

    @Inject lateinit var guest: ShareNodesHandler
    @Inject lateinit var nodes: NodesHandler
    @Inject lateinit var sharing: SharingHandler
    @Inject lateinit var store: SharingStore

    /** `outer` at the scope root holds the shared `root` and `outside`; `root` holds `a` and `x`. */
    private data class Tree(
        val userId: String, val scopeId: String, val outer: String, val root: String, val outside: String,
        val a: String, val x: String, val link: GuestTreeShare.Link,
    )

    private fun tree(): Tree {
        val (userId, scopeId) = seedMemberScope()
        val outer = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val root = seedNode(scopeId, NodeEntity.FOLDER, parentId = outer, wrappedContentKey = CONTENT_KEY)
        val outside = seedNode(scopeId, NodeEntity.FOLDER, parentId = outer, wrappedContentKey = CONTENT_KEY)
        val a = seedNode(scopeId, NodeEntity.FOLDER, parentId = root, wrappedContentKey = CONTENT_KEY)
        val x = seedNode(scopeId, NodeEntity.FOLDER, parentId = root, wrappedContentKey = CONTENT_KEY)
        return Tree(userId, scopeId, outer, root, outside, a, x, GuestTreeShare.seed(userId, scopeId, root))
    }

    @Test
    fun aGuestMoveIntoAFolderAMemberMovesOutOfTheSubtreeMeanwhileIsRefused() {
        raceRounds { round ->
            val t = tree()
            val loser = whileUncommitted(
                change = { assertEquals(UpdateNodeOutcome.Updated, memberMoves(t, t.a, t.outside), "round $round: a out") },
                overlapping = { guestMoves(t, t.x, t.a) },
            )
            assertEquals(UpdateShareNodeOutcome.IllegalStructure, loser, "round $round: x into a, after a left the subtree")
            assertEquals(t.root, parentOf(t.x), "round $round: x stays inside the subtree")
        }
        val t = tree() // control
        assertEquals(UpdateShareNodeOutcome.Updated, guestMoves(t, t.x, t.a))
        assertEquals(t.a, parentOf(t.x))
    }

    @Test
    fun aGuestCreateUnderAParentAMemberTrashesMeanwhileIsRefused() {
        raceRounds { round ->
            val t = tree()
            val id = UUID.randomUUID().toString()
            val loser = whileUncommitted(
                change = { assertEquals(DeleteNodeOutcome.Deleted, nodes.delete(t.userId, t.scopeId, t.a), "round $round: a trashed") },
                overlapping = { guestCreates(t, t.a, id) },
            )
            assertEquals(CreateShareNodeOutcome.IllegalStructure, loser, "round $round: a create under a trashed parent")
            assertNull(row(id), "round $round: no child under a trashed parent")
        }
        val t = tree() // control
        val id = UUID.randomUUID().toString()
        assertEquals(CreateShareNodeOutcome.Created(id), guestCreates(t, t.a, id))
    }

    @Test
    fun aRevokedOrDeletedLinkNeverAcceptsAGuestWriteMeanwhile() {
        raceRounds { round ->
            for ((what, change) in listOf<Pair<String, (Tree) -> Unit>>(
                "revoke" to { t -> assertEquals(true, store.revoke(t.userId, t.link.shareId)) },
                "delete" to { t -> assertEquals(true, store.delete(t.link.shareId)) },
            )) {
                val t = tree()
                val id = UUID.randomUUID().toString()
                val created = whileUncommitted(change = { change(t) }, overlapping = { guestCreates(t, t.root, id) })
                assertEquals(CreateShareNodeOutcome.NotFound, created, "round $round: a create while the $what commits")
                assertNull(row(id), "round $round: nothing created on a link the $what took")
                val t2 = tree()
                val moved = whileUncommitted(change = { change(t2) }, overlapping = { guestMoves(t2, t2.x, t2.a) })
                assertEquals(UpdateShareNodeOutcome.NotFound, moved, "round $round: a move while the $what commits")
                assertEquals(t2.root, parentOf(t2.x), "round $round: nothing moved on a link the $what took")
            }
        }
    }

    @Test
    fun aGuestWriteAndAReactivationOfTheLinkBothLandWithoutADeadlock() {
        raceRounds { round ->
            val t = tree()
            val id = UUID.randomUUID().toString()
            val pool = Executors.newFixedThreadPool(2)
            try {
                val scopeHeld = CountDownLatch(1)
                // The re-activation's own order, held open: the scope row first, then — after the guest has started —
                // the share row (SharingHandler.reactivate re-takes the scope and then locks the share).
                val reactivation = pool.submit(
                    Callable {
                        QuarkusTransaction.requiringNew().call {
                            ScopeEntity.lockRow(t.scopeId)
                            scopeHeld.countDown()
                            val overlap = holdForOverlap() // the guest's create reaches the scope row meanwhile
                            overlap to sharing.reactivate(t.userId, t.link.shareId)
                        }
                    },
                )
                if (!scopeHeld.await(10, TimeUnit.SECONDS)) reactivation.get(1, TimeUnit.SECONDS)
                val create = pool.submit(Callable { inRequest { guestCreates(t, t.root, id) } })
                val (overlap, reactivated) = reactivation.get(30, TimeUnit.SECONDS)
                val created = create.get(30, TimeUnit.SECONDS)
                overlap.requireForced()
                assertEquals(ReactivateShareOutcome.Done, reactivated, "round $round: the re-activation")
                assertEquals(CreateShareNodeOutcome.Created(id), created, "round $round: the guest's create")
            } finally {
                pool.shutdownNow()
            }
        }
    }

    // --- helpers ---

    private fun memberMoves(t: Tree, node: String, to: String): UpdateNodeOutcome =
        nodes.update(t.userId, t.scopeId, node, placementTo(to), null, null, null)

    private fun guestMoves(t: Tree, node: String, to: String): UpdateShareNodeOutcome =
        guest.update(t.link.shareId, null, t.link.token, node, to, "cGxhY2Vk", GuestTreeShare.slug(), "cmUtd3JhcHBlZA==", 0, seen = null)

    private fun guestCreates(t: Tree, parent: String, id: String): CreateShareNodeOutcome =
        guest.create(t.link.shareId, null, t.link.token, id, parent, NodeEntity.FOLDER, "bmV3", GuestTreeShare.slug(), 0, 0, CONTENT_KEY, seen = null)

    private fun <T> inRequest(body: () -> T): T {
        val request = Arc.container().requestContext()
        request.activate()
        try {
            return body()
        } finally {
            request.terminate()
        }
    }

    private fun parentOf(node: String): String = QuarkusTransaction.requiringNew().call { NodeEntity.findById(node)!!.parentId }
    private fun row(id: String): NodeEntity? = QuarkusTransaction.requiringNew().call { NodeEntity.findById(id) }
}
