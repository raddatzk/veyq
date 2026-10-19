package me.raddatz.veyq.core.items

import me.raddatz.veyq.core.content.NodeSubtreePurge
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.spaces.scope.ScopePurge
import me.raddatz.veyq.core.testing.RACE_ROUNDS
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.race
import me.raddatz.veyq.core.testing.whileUncommitted
import io.quarkus.arc.Arc
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The platform's item-list primitive (inbox #410, ADR 0033) with core alone: the counters' birth and arithmetic, the
 * bump under overlap, where the table sits in the lock order, the conditional read's three answers and the
 * erasure that leaves no row behind. Memories' own paths (`MediaSliceTest` and the media races) prove the same
 * primitive from the application side.
 */
@QuarkusTest
class NodeItemsRevisionsCoreSliceTest {

    @Inject lateinit var items: NodeItemsRevisions
    @Inject lateinit var subtreePurge: NodeSubtreePurge
    @Inject lateinit var scopePurge: ScopePurge

    // --- the counters ---

    @Test
    fun noRowReadsZeroAndTheFirstBumpBirthsIt() {
        val (_, scopeId) = seedMemberScope()
        val node = folder(scopeId)
        QuarkusTransaction.requiringNew().run { assertNull(NodeItemsRevisionEntity.findById(node), "no change yet, no row") }
        assertEquals(ItemsRevisions(0, 0), revisions(node), "no row reads as 0/0")

        bump(scopeId, node)
        assertEquals(ItemsRevisions(1, 0), revisions(node), "an add moves the revision only")

        bump(scopeId, node, deleted = true)
        assertEquals(ItemsRevisions(2, 1), revisions(node), "a removal moves both, on the row the first bump made")

        bump(scopeId, node)
        assertEquals(ItemsRevisions(3, 1), revisions(node), "an edit after a removal leaves the delete revision where it was")
    }

    @Test
    fun aBumpAnswersTheRevisionItWroteTheItemsPosition() {
        val (_, scopeId) = seedMemberScope()
        val node = folder(scopeId)
        assertEquals(1, bump(scopeId, node), "the first bump births the row at 1")
        assertEquals(2, bump(scopeId, node, deleted = true), "a removal's bump answers the revision too")
        // Inside one transaction a second bump answers its own write, not a copy the session loaded before it.
        val both = QuarkusTransaction.requiringNew().call {
            items.revisions(node) // loads the row into the session
            listOf(items.bump(scopeId, node), items.bump(scopeId, node))
        }
        assertEquals(listOf(3, 4), both)
    }

    @Test
    fun aBumpIsPerNode() {
        val (_, scopeId) = seedMemberScope()
        val a = folder(scopeId)
        val b = folder(scopeId)
        bump(scopeId, a, deleted = true)
        assertEquals(ItemsRevisions(1, 1), revisions(a))
        assertEquals(ItemsRevisions(0, 0), revisions(b), "a neighbour's counters do not move")
    }

    // --- overlap ---

    /**
     * Two first bumps: neither finds a row to UPDATE (READ COMMITTED takes no lock on a miss), so the insert is
     * decided under the node row's lock with the UPDATE asked again beneath it. The overlapping bump waits for the
     * first's commit and then bumps its row. Without the second ask it inserted too and died at the primary key.
     */
    @Test
    fun twoFirstBumpsOverlappingCountBoth() {
        repeat(RACE_ROUNDS) { round ->
            val (_, scopeId) = seedMemberScope()
            val node = folder(scopeId)
            whileUncommitted(
                change = { items.bump(scopeId, node) },
                overlapping = { items.bump(scopeId, node, deleted = true) },
            )
            assertEquals(ItemsRevisions(2, 1), revisions(node), "round $round: both first bumps counted, on one row")
        }
    }

    /** Many writers at once, first bump included: the increment is one atomic UPDATE, never a read-add-write. */
    @Test
    fun concurrentBumpsAllCount() {
        repeat(RACE_ROUNDS) { round ->
            val (_, scopeId) = seedMemberScope()
            val node = folder(scopeId)
            val results = race(*Array(WRITERS) { i -> { runCatching { bump(scopeId, node, deleted = i % 2 == 0) } } })
            assertEquals(emptyList<Throwable>(), results.mapNotNull { it.exceptionOrNull() }, "round $round: no bump dies")
            assertEquals(ItemsRevisions(WRITERS, WRITERS / 2), revisions(node), "round $round: every bump is counted")
        }
    }

    /**
     * The revision row is a leaf. A bump of a node that already has its row UPDATEs that row and nothing else, so it
     * neither waits for the node row's lock nor holds it: a writer that holds the node row open (the node's own writes
     * do) never stalls a bump, and no order between the two can deadlock.
     */
    @Test
    fun aBumpOfAnExistingRowNeverWaitsForTheNodeRow() {
        val (_, scopeId) = seedMemberScope()
        val node = folder(scopeId)
        bump(scopeId, node) // the row exists now
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bumped = AtomicBoolean(false)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val holder = pool.submit {
                QuarkusTransaction.requiringNew().run {
                    NodeEntity.findById(node, LockModeType.PESSIMISTIC_WRITE)
                    Arc.container().instance(EntityManager::class.java).get().flush()
                    locked.countDown()
                    check(release.await(30, TimeUnit.SECONDS)) { "never released" }
                }
            }
            assertTrue(locked.await(10, TimeUnit.SECONDS), "the node row was never locked")
            val bump = pool.submit { bump(scopeId, node, deleted = true); bumped.set(true) }
            bump.get(15, TimeUnit.SECONDS) // finishes while the node row is still held
            assertTrue(bumped.get(), "the bump finished with the node row held by another transaction")
            release.countDown()
            holder.get(15, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
        assertEquals(ItemsRevisions(2, 1), revisions(node))
    }

    // --- the conditional read ---

    @Test
    fun anAbsentRowReadsZeroSoAClientHoldingZeroGetsNoBody() {
        val (_, scopeId) = seedMemberScope()
        val node = folder(scopeId)
        val first = read(node, ifNoneMatch = null, sinceDelete = 0, delta = listOf("a"), ids = listOf("a"))
        assertEquals(ItemsRead.Changed(ItemsRevisions(0, 0), ItemsPage(listOf("a"), false), null), first, "first load: the delta, no id set")
        assertEquals(ItemsRead.NotModified, read(node, ifNoneMatch = 0, sinceDelete = 0, delta = listOf("x"), ids = emptyList()), "an echoed 0 matches the absent row")
    }

    @Test
    fun anUnchangedRevisionIsANotModifiedAndRunsNeitherQuery() {
        val (_, scopeId) = seedMemberScope()
        val node = folder(scopeId)
        bump(scopeId, node)
        var queried = false
        val answer = QuarkusTransaction.requiringNew().call {
            items.read(node, ifNoneMatch = 1, sinceDelete = 0, delta = { queried = true; ItemsPage(emptyList<String>(), false) }, ids = { queried = true; emptyList() })
        }
        assertEquals(ItemsRead.NotModified, answer)
        assertTrue(!queried, "the idle fast path runs no item query")
    }

    @Test
    fun aChangedRevisionIsTheDeltaAndTheIdSetOnlyWhenADeleteAdvancedPastTheClient() {
        val (_, scopeId) = seedMemberScope()
        val node = folder(scopeId)
        bump(scopeId, node)
        bump(scopeId, node) // revision 2, delete revision 0
        val addsOnly = read(node, ifNoneMatch = 1, sinceDelete = 0, delta = listOf("d"), ids = listOf("a", "d"))
        assertEquals(ItemsRead.Changed(ItemsRevisions(2, 0), ItemsPage(listOf("d"), false), null), addsOnly, "adds and edits stay delta-only")

        bump(scopeId, node, deleted = true) // revision 3, delete revision 1
        val afterDelete = read(node, ifNoneMatch = 2, sinceDelete = 0, delta = emptyList(), ids = listOf("a"))
        assertEquals(ItemsRead.Changed(ItemsRevisions(3, 1), ItemsPage(emptyList<String>(), false), listOf("a")), afterDelete, "a client behind the delete gets the id set")

        val synced = read(node, ifNoneMatch = 2, sinceDelete = 1, delta = emptyList(), ids = listOf("a"))
        assertEquals(ItemsRead.Changed(ItemsRevisions(3, 1), ItemsPage(emptyList<String>(), false), null), synced, "a client that echoed the delete revision is not sent the O(N) set again")
    }

    @Test
    fun theEtagAndTheCursorRoundTripLeniently() {
        assertEquals("\"7\"", ItemsETag.of(7))
        assertEquals(7, ItemsETag.parse("\"7\""))
        assertEquals(7, ItemsETag.parse("W/\"7\""))
        assertEquals(7, ItemsETag.parse(" 7 "))
        assertNull(ItemsETag.parse("abc"))
        assertNull(ItemsETag.parse(null))

        val cursor = ItemsCursor.parse("17,2a9b1d4a-6e8b-2c1f-2d3e-4a5b6c7c3f0e")
        assertEquals(ItemsCursor(17, "2a9b1d4a-6e8b-2c1f-2d3e-4a5b6c7c3f0e"), cursor)
        assertEquals(cursor, ItemsCursor.parse(cursor!!.wire()), "a page's cursor reads back as itself")
        assertEquals(cursor, ItemsCursor.parse(" 17 , 2a9b1d4a-6e8b-2c1f-2d3e-4a5b6c7c3f0e "), "blanks around the parts are no part of them")
        assertNull(ItemsCursor.parse(null))
        assertNull(ItemsCursor.parse("nonsense"))
        assertNull(ItemsCursor.parse("17,"))
        assertNull(ItemsCursor.parse("-1,abc"))
        assertNull(ItemsCursor.parse("2026-07-09T12:34:56Z,abc"), "a timestamp is no position")
    }

    // --- erasure ---

    @Test
    fun theTrashPurgeOfANodeErasesItsCountersAndLeavesNeighboursAlone() {
        val (_, scopeId) = seedMemberScope()
        val gone = folder(scopeId)
        val kept = folder(scopeId)
        bump(scopeId, gone)
        bump(scopeId, kept)
        QuarkusTransaction.requiringNew().run { NodeEntity.findById(gone)!!.deletedAt = Instant.now().minusSeconds(3600) }

        // fk_items_revision_node refuses the node delete if the eraser missed the row, so a green purge is the proof
        QuarkusTransaction.requiringNew().run { subtreePurge.eraseExpiredIn(scopeId, Instant.now()) }

        QuarkusTransaction.requiringNew().run {
            assertNull(NodeEntity.findById(gone), "the node is gone")
            assertNull(NodeItemsRevisionEntity.findById(gone), "and its counters with it")
            assertNotNull(NodeItemsRevisionEntity.findById(kept), "a neighbour's counters stay")
        }
    }

    @Test
    fun theScopePurgeErasesEveryCounterOfTheScope() {
        val (_, scopeId) = seedMemberScope()
        val nodes = List(3) { folder(scopeId) }
        nodes.forEach { bump(scopeId, it, deleted = true) }

        scopePurge.purge(scopeId) // throws on an FK refusal if the counters outlived the node delete

        QuarkusTransaction.requiringNew().run {
            assertNull(ScopeEntity.findById(scopeId), "the scope is gone")
            assertEquals(0L, NodeItemsRevisionEntity.count("nodeId in ?1", nodes), "no counter row is left behind")
        }
    }

    // --- helpers ---

    private fun folder(scopeId: String): String = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)

    private fun bump(scopeId: String, nodeId: String, deleted: Boolean = false): Int =
        QuarkusTransaction.requiringNew().call { items.bump(scopeId, nodeId, deleted) }

    private fun revisions(nodeId: String): ItemsRevisions = QuarkusTransaction.requiringNew().call { items.revisions(nodeId) }

    private fun read(node: String, ifNoneMatch: Int?, sinceDelete: Int, delta: List<String>, ids: List<String>): ItemsRead<String> =
        QuarkusTransaction.requiringNew().call { items.read(node, ifNoneMatch, sinceDelete, delta = { ItemsPage(delta, false) }, ids = { ids }) }

    private companion object {
        const val WRITERS = 6
    }
}
