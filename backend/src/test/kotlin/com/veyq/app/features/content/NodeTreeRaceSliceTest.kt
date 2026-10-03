package com.veyq.app.features.content

import com.veyq.app.shared.config.VeyqConfig
import com.veyq.app.testing.RACE_ROUNDS
import com.veyq.app.testing.TestFixtures.CONTENT_KEY
import com.veyq.app.testing.TestFixtures.seedMemberScope
import com.veyq.app.testing.TestFixtures.seedNode
import com.veyq.app.testing.whileUncommitted
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * The scope row serializes a scope's node tree (developer-styleguide §12): a create, a move, a trash, a restore and
 * the trash purge each take it first, and decide under it. A trash enumerates the subtree it stamps; a create or a
 * move that checked its parent before that trash committed and wrote after it left a live node under a trashed
 * parent — in no listing, and orphaned by the purge (`parent_id` has no foreign key). A purge enumerated what was
 * due before a restore committed and erased the restored subtree; a restore of a subtree the purge was erasing died
 * at its flush. Neither order of the two writes produces any of these.
 *
 * Each race holds one side's commit back ([whileUncommitted]) and runs the other inside that window. Each has a
 * control: the same write with nothing overlapping lands.
 */
@QuarkusTest
class NodeTreeRaceSliceTest {

    @Inject lateinit var handler: NodesHandler
    @Inject lateinit var store: NodesStore
    @Inject lateinit var purge: NodeSubtreePurge
    @Inject lateinit var config: VeyqConfig

    @Test
    fun aNodeCreatedUnderAFolderBeingTrashedIsRefused() {
        repeat(RACE_ROUNDS) { round ->
            val (userId, scopeId) = seedMemberScope()
            val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = null)
            val child = UUID.randomUUID().toString()
            val outcome = whileUncommitted(
                change = { store.deleteSubtree(scopeId, folder) },
                overlapping = { createMemory(userId, scopeId, child, folder) },
            )
            assertEquals(CreateNodeOutcome.IllegalStructure, outcome, "round $round: a node was created under a trashed folder")
            assertNull(tx { NodeEntity.findById(child) }, "round $round: nothing written")
            // Control: the same create under a folder nobody trashes lands.
            val live = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = null)
            val fresh = UUID.randomUUID().toString()
            assertEquals(CreateNodeOutcome.Created(fresh), createMemory(userId, scopeId, fresh, live), "round $round: control")
        }
    }

    @Test
    fun aNodeMovedUnderAFolderBeingTrashedIsRefused() {
        repeat(RACE_ROUNDS) { round ->
            val (userId, scopeId) = seedMemberScope()
            val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = null)
            val memory = seedNode(scopeId, NodeEntity.MEMORY, wrappedContentKey = CONTENT_KEY)
            val outcome = whileUncommitted(
                change = { store.deleteSubtree(scopeId, folder) },
                overlapping = { move(userId, scopeId, memory, folder) },
            )
            assertEquals(UpdateNodeOutcome.IllegalStructure, outcome, "round $round: a node was moved under a trashed folder")
            tx {
                val row = NodeEntity.findById(memory)!!
                assertEquals(scopeId, row.parentId, "round $round: the node stays where it was")
                assertNull(row.deletedAt, "round $round")
            }
            val live = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = null)
            assertEquals(UpdateNodeOutcome.Updated, move(userId, scopeId, memory, live), "round $round: control")
        }
    }

    @Test
    fun aSubtreeRestoredWhileThePurgeRunsSurvives() {
        repeat(RACE_ROUNDS) { round ->
            val (_, scopeId) = seedMemberScope()
            val (memory, chapter) = expiredSubtree(scopeId)
            whileUncommitted(
                change = { assertEquals(NodesStore.RestoreResult.DONE, store.restoreSubtree(scopeId, memory, slug(), null), "round $round") },
                overlapping = { purge.run() },
            )
            tx {
                listOf(memory, chapter).forEach {
                    val row = NodeEntity.findById(it)
                    assertNotNull(row, "round $round: the purge erased a restored node")
                    assertNull(row!!.deletedAt, "round $round")
                }
            }
        }
        val (_, scopeId) = seedMemberScope()
        val (memory, chapter) = expiredSubtree(scopeId)
        purge.run()
        assertEquals(listOf(null, null), tx { listOf(memory, chapter).map { NodeEntity.findById(it) } }, "control: an expired subtree is erased")
    }

    @Test
    fun aRestoreOfASubtreeThePurgeIsErasingAnswersNotFound() {
        repeat(RACE_ROUNDS) { round ->
            val (_, scopeId) = seedMemberScope()
            val (memory, _) = expiredSubtree(scopeId)
            val restored = whileUncommitted(
                change = { purge.eraseExpiredIn(scopeId, cutoff()) },
                overlapping = { runCatching { store.restoreSubtree(scopeId, memory, slug(), null) } },
            )
            assertEquals(Result.success(NodesStore.RestoreResult.NOT_FOUND), restored, "round $round")
            assertNull(tx { NodeEntity.findById(memory) }, "round $round")
        }
    }

    // --- helpers ---

    private fun createMemory(userId: String, scopeId: String, nodeId: String, parent: String): CreateNodeOutcome =
        handler.create(userId, scopeId, nodeId, parent, NodeEntity.MEMORY, "bmV3", slug(), 0, 0, CONTENT_KEY, null)

    private fun move(userId: String, scopeId: String, nodeId: String, parent: String): UpdateNodeOutcome =
        handler.update(userId, scopeId, nodeId, null, null, parent, null, null, null)

    /** A trashed Memory with a Chapter under it, both stamped past the retention as one delete stamps them.
     *  Returns (memory, chapter). */
    private fun expiredSubtree(scopeId: String): Pair<String, String> {
        val memory = seedNode(scopeId, NodeEntity.MEMORY, wrappedContentKey = CONTENT_KEY)
        val chapter = seedNode(scopeId, NodeEntity.CHAPTER, parentId = memory, wrappedContentKey = CONTENT_KEY)
        val stamp = cutoff().minusSeconds(60)
        tx { listOf(memory, chapter).forEach { NodeEntity.findById(it)!!.apply { deletedAt = stamp; slugToken = id } } }
        return memory to chapter
    }

    private fun cutoff(): Instant = Instant.now().minus(config.node().trash().retention())

    private fun slug(): String = Base64.getEncoder().encodeToString(UUID.randomUUID().toString().toByteArray())

    private fun <T> tx(body: () -> T): T = QuarkusTransaction.requiringNew().call(body)
}
