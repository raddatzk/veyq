package me.raddatz.veyq.core.content

import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.testing.RACE_ROUNDS
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.placementTo
import me.raddatz.veyq.core.testing.whileUncommitted
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.time.Instant
import java.util.Base64
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
 *
 * **Split from `NodeTreeRaceSliceTest`** (cr-platform-gradle-modules step 8a): these methods run with core alone, so they live in
 * `:core`; the rest of the class is `NodeTreeRaceSliceTest` in `:apps:memories`. The bodies moved verbatim,
 * but for `MemoriesNodeKinds.MEMORY` (a Memories class `:core` cannot import) spelled as its wire literal `"MEMORY"`.
 */
@QuarkusTest
class NodeTreeRaceCoreSliceTest {

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

    /**
     * ADR 0080 Decision 8 / bite test 8 under overlap: a child (trashed earlier, a trash root while its parent is live)
     * restored while its parent's trash is uncommitted. The restore waits for the trash on the scope lock and then reads
     * the parent trashed: `NOT_FOUND`, the child stays in the trash on the parent's marker. Restored on the parent read
     * live before the trash committed, it would come back live under a trashed parent whose key the purge erases.
     */
    @Test
    fun aChildRestoredWhileItsParentIsTrashedStaysInTheTrash() {
        repeat(RACE_ROUNDS) { round ->
            val (_, scopeId) = seedMemberScope()
            val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
            val child = seedNode(scopeId, NodeEntity.FOLDER, parentId = folder, wrappedContentKey = CONTENT_KEY)
            store.deleteSubtree(scopeId, child)
            val restored = whileUncommitted(
                change = { store.deleteSubtree(scopeId, folder) },
                overlapping = { store.restoreSubtree(scopeId, child, slug(), null) },
            )
            assertEquals(NodesStore.RestoreResult.NOT_FOUND, restored, "round $round: a child restored under a trashed parent")
            tx {
                val (f, c) = listOf(folder, child).map { NodeEntity.findById(it)!! }
                assertNotNull(c.deletedAt, "round $round: the child is live under a trashed parent")
                assertEquals(f.deletedAt, c.deletedAt, "round $round: the subtree is on one marker")
            }
        }
        // control: with its parent live the same restore lands
        val (_, scopeId) = seedMemberScope()
        val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val child = seedNode(scopeId, NodeEntity.FOLDER, parentId = folder, wrappedContentKey = CONTENT_KEY)
        store.deleteSubtree(scopeId, child)
        assertEquals(NodesStore.RestoreResult.DONE, store.restoreSubtree(scopeId, child, slug(), null), "control")
    }

    /**
     * The other order of `NodeTreeRaceSliceTest`'s create and move races: the create (or the move) holds its commit
     * while the parent's trash runs. The trash waits on the scope lock, then enumerates the committed child and stamps
     * it with the subtree's marker — never a live node under a trashed parent.
     */
    @Test
    fun aChildCreatedOrMovedBeforeItsParentsTrashCommitsIsTrashedWithIt() {
        repeat(RACE_ROUNDS) { round ->
            for (how in listOf("create", "move")) {
                val (_, scopeId) = seedMemberScope()
                val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
                val child = if (how == "move") seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY) else UUID.randomUUID().toString()
                whileUncommitted(
                    change = {
                        if (how == "create") {
                            // the create's own transaction body, as `NodesHandler.create` runs it in `createOrConflict`
                            store.lockScope(scopeId)
                            assertTrue(store.create(child, scopeId, folder, NodeEntity.FOLDER, "bmV3", slug(), 0, 0, CONTENT_KEY, null))
                        } else {
                            assertEquals(
                                NodesStore.ApplyResult.Applied,
                                store.applyUpdate(scopeId, child, NodesStore.Placement(folder, "bmV3", slug(), CONTENT_KEY, null, 0), null, null, null),
                            )
                        }
                    },
                    overlapping = { store.deleteSubtree(scopeId, folder) },
                )
                tx {
                    val (f, c) = listOf(folder, child).map { NodeEntity.findById(it)!! }
                    assertEquals(folder, c.parentId, "round $round ($how)")
                    assertNotNull(f.deletedAt, "round $round ($how)")
                    assertEquals(f.deletedAt, c.deletedAt, "round $round ($how): the child escaped its parent's trash")
                }
            }
        }
    }

    @Inject lateinit var handler: NodesHandler

    @Inject lateinit var store: NodesStore

    @Inject lateinit var purge: NodeSubtreePurge

    @Inject lateinit var config: VeyqConfig

    // --- helpers ---

    private fun move(userId: String, scopeId: String, nodeId: String, parent: String): UpdateNodeOutcome =
        handler.update(userId, scopeId, nodeId, placementTo(parent), null, null, null)

    /** A trashed Memory with a Chapter under it, both stamped past the retention as one delete stamps them.
     *  Returns (memory, chapter). */
    private fun expiredSubtree(scopeId: String): Pair<String, String> {
        val memory = seedNode(scopeId, "MEMORY", wrappedContentKey = CONTENT_KEY)
        val chapter = seedNode(scopeId, "CHAPTER", parentId = memory, wrappedContentKey = CONTENT_KEY)
        val stamp = cutoff().minusSeconds(60)
        tx { listOf(memory, chapter).forEach { NodeEntity.findById(it)!!.apply { deletedAt = stamp; slugToken = id } } }
        return memory to chapter
    }

    private fun cutoff(): Instant = Instant.now().minus(config.node().trash().retention())

    private fun slug(): String = Base64.getEncoder().encodeToString(UUID.randomUUID().toString().toByteArray())

    private fun <T> tx(body: () -> T): T = QuarkusTransaction.requiringNew().call(body)
}
