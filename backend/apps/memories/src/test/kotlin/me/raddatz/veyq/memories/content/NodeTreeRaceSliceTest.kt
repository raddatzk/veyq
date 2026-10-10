package me.raddatz.veyq.memories.content

import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.memories.media.MediaEntity
import me.raddatz.veyq.memories.media.MediaStore
import me.raddatz.veyq.core.testing.TestBlob
import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.testing.RACE_ROUNDS
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.placementTo
import me.raddatz.veyq.core.testing.whileUncommitted
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.time.Instant
import java.util.Base64
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import me.raddatz.veyq.core.content.CreateNodeOutcome
import me.raddatz.veyq.core.content.NodeSubtreePurge
import me.raddatz.veyq.core.content.NodesHandler
import me.raddatz.veyq.core.content.NodesStore
import me.raddatz.veyq.core.content.UpdateNodeOutcome

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
 * **Split** (cr-platform-gradle-modules step 8a): the methods that run with core alone are `NodeTreeRaceCoreSliceTest` in `:core`.
 */
@QuarkusTest
class NodeTreeRaceSliceTest {

    @Inject lateinit var handler: NodesHandler

    @Inject lateinit var store: NodesStore

    @Inject lateinit var purge: NodeSubtreePurge

    @Inject lateinit var config: VeyqConfig

    @Inject lateinit var media: MediaStore

    @Test
    fun aNodeCreatedUnderAFolderBeingTrashedIsRefused() {
        repeat(RACE_ROUNDS) { round ->
            val (userId, scopeId) = seedMemberScope()
            val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
            val child = UUID.randomUUID().toString()
            val outcome = whileUncommitted(
                change = { store.deleteSubtree(scopeId, folder) },
                overlapping = { createMemory(userId, scopeId, child, folder) },
            )
            assertEquals(CreateNodeOutcome.IllegalStructure, outcome, "round $round: a node was created under a trashed folder")
            assertNull(tx { NodeEntity.findById(child) }, "round $round: nothing written")
            // Control: the same create under a folder nobody trashes lands.
            val live = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
            val fresh = UUID.randomUUID().toString()
            assertEquals(CreateNodeOutcome.Created(fresh), createMemory(userId, scopeId, fresh, live), "round $round: control")
        }
    }

    @Test
    fun aNodeMovedUnderAFolderBeingTrashedIsRefused() {
        repeat(RACE_ROUNDS) { round ->
            val (userId, scopeId) = seedMemberScope()
            val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
            val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
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
            val live = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
            assertEquals(UpdateNodeOutcome.Updated, move(userId, scopeId, memory, live), "round $round: control")
        }
    }

    /**
     * A trashed photo purged while the trash purge erases its Memory: the erase selected the photo unlocked, and its
     * delete of a row the photo's purge had just removed rolled the whole erase back — retried next run, so nothing
     * was lost, but the subtree could starve. Selected under the lock, it waits for the photo's purge and skips it.
     */
    @Test
    fun aPhotoPurgedWhileItsSubtreeIsErasedDoesNotRollTheEraseBack() {
        repeat(RACE_ROUNDS) { round ->
            val (_, scopeId) = seedMemberScope()
            val (memory, chapter) = expiredSubtree(scopeId)
            val photo = trashedPhoto(scopeId, memory)
            whileUncommitted(
                change = { assertTrue(media.purgeMedia(scopeId, photo), "round $round") },
                overlapping = { purge.run() },
            )
            assertEquals(listOf(null, null), tx { listOf(memory, chapter).map { NodeEntity.findById(it) } }, "round $round: the erase rolled back")
        }
    }

    // --- helpers ---

    /** A trashed photo in [nodeId] — one the member-expedited purge may erase. */
    private fun trashedPhoto(scopeId: String, nodeId: String): String {
        val mediaId = UUID.randomUUID().toString()
        tx {
            val blobId = UUID.randomUUID().toString()
            BlobEntity().apply {
                id = blobId; this.scopeId = scopeId; storageKey = UUID.randomUUID().toString()
                chunkCount = TestBlob.SMALL.chunkCount; byteSize = TestBlob.SMALL.byteSize; wrappedBlobKey = "Ymxvyi13cmFw"
            }.persist()
            MediaEntity().apply {
                id = mediaId; this.scopeId = scopeId; this.nodeId = nodeId; this.blobId = blobId
                createdAt = Instant.now(); deletedAt = Instant.now()
            }.persist()
        }
        return mediaId
    }

    private fun createMemory(userId: String, scopeId: String, nodeId: String, parent: String): CreateNodeOutcome =
        handler.create(userId, scopeId, nodeId, parent, MemoriesNodeKinds.MEMORY, "bmV3", slug(), 0, 0, CONTENT_KEY, null)

    private fun move(userId: String, scopeId: String, nodeId: String, parent: String): UpdateNodeOutcome =
        handler.update(userId, scopeId, nodeId, placementTo(parent), null, null, null)

    /** A trashed Memory with a Chapter under it, both stamped past the retention as one delete stamps them — and,
     *  as it does, only the root's slug token freed (a freed descendant would stay in the trash on a restore, #357).
     *  Returns (memory, chapter). */
    private fun expiredSubtree(scopeId: String): Pair<String, String> {
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val chapter = seedNode(scopeId, MemoriesNodeKinds.CHAPTER, parentId = memory, wrappedContentKey = CONTENT_KEY)
        val stamp = cutoff().minusSeconds(60)
        tx {
            listOf(memory, chapter).forEach { NodeEntity.findById(it)!!.deletedAt = stamp }
            NodeEntity.findById(memory)!!.slugToken = memory
        }
        return memory to chapter
    }

    private fun cutoff(): Instant = Instant.now().minus(config.node().trash().retention())

    private fun slug(): String = Base64.getEncoder().encodeToString(UUID.randomUUID().toString().toByteArray())

    private fun <T> tx(body: () -> T): T = QuarkusTransaction.requiringNew().call(body)
}
