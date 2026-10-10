package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.testing.RACE_ROUNDS
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.whileUncommitted
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A Memory's gallery counters as Memories' own row (`veyq_media_revision`, inbox #341) — two columns of core's
 * `veyq_node` before, where the row always existed and a bump was one UPDATE. Now the node's first change births the
 * row, so what the move could break is pinned here: no row reads as the columns' old default, a bump moves the
 * counters as it did, and two first bumps racing both count — the second never dies at the primary key.
 */
@QuarkusTest
class MediaRevisionSliceTest {

    @Inject
    lateinit var store: MediaStore

    @Test
    fun noRowReadsZeroAndTheFirstBumpBirthsIt() {
        val (_, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        QuarkusTransaction.requiringNew().run { assertNull(MediaRevisionEntity.findById(memory), "no change yet, no row") }
        assertEquals(0 to 0, revisions(memory), "no row reads as the columns' old default")

        QuarkusTransaction.requiringNew().run { store.bumpMediaRevision(memory) }
        assertEquals(1 to 0, revisions(memory), "an add moves media_revision only")

        QuarkusTransaction.requiringNew().run { store.bumpMediaRevision(memory, deleted = true) }
        assertEquals(2 to 1, revisions(memory), "a removal moves both, on the row the first bump made")
    }

    /**
     * Two first bumps: neither finds a row to UPDATE (READ COMMITTED takes no lock on a miss), so the insert is
     * decided under the node row's lock with the UPDATE asked again beneath it. The overlapping bump waits for the
     * first's commit and then bumps its row. Without the second ask it inserted too and died at the primary key.
     */
    @Test
    fun twoFirstBumpsOverlappingCountBoth() {
        repeat(RACE_ROUNDS) { round ->
            val (_, scopeId) = seedMemberScope()
            val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
            whileUncommitted(
                change = { store.bumpMediaRevision(memory) },
                overlapping = { store.bumpMediaRevision(memory, deleted = true) },
            )
            assertEquals(2 to 1, revisions(memory), "round $round: both first bumps counted, on one row")
        }
    }

    private fun revisions(nodeId: String): Pair<Int, Int> = QuarkusTransaction.requiringNew().call { store.revisions(nodeId) }
}
