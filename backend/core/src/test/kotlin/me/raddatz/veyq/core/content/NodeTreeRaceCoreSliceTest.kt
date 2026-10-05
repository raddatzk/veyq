package com.veyq.core.content

import com.veyq.core.node.NodeEntity
import com.veyq.core.config.VeyqConfig
import com.veyq.core.testing.RACE_ROUNDS
import com.veyq.core.testing.TestFixtures.CONTENT_KEY
import com.veyq.core.testing.TestFixtures.seedMemberScope
import com.veyq.core.testing.TestFixtures.seedNode
import com.veyq.core.testing.whileUncommitted
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.time.Instant
import java.util.Base64
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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

    @Inject lateinit var handler: NodesHandler

    @Inject lateinit var store: NodesStore

    @Inject lateinit var purge: NodeSubtreePurge

    @Inject lateinit var config: VeyqConfig

    // --- helpers ---

    private fun move(userId: String, scopeId: String, nodeId: String, parent: String): UpdateNodeOutcome =
        handler.update(userId, scopeId, nodeId, null, null, parent, null, null, null)

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
