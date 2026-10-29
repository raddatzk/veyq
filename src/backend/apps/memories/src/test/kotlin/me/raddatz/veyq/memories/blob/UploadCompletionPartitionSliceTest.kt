package me.raddatz.veyq.memories.blob

import me.raddatz.veyq.core.content.NodesStore
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.memories.media.MediaUploadCompletion
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.arc.All
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import me.raddatz.veyq.core.blob.CompleteItem
import me.raddatz.veyq.core.blob.CompleteItemResult
import me.raddatz.veyq.core.blob.UploadCompletion
import me.raddatz.veyq.core.blob.UploadCompletions
import me.raddatz.veyq.core.blob.UploadOpener

/**
 * Core's completion dispatcher ([UploadCompletions]) offers an item to **exactly one** [UploadCompletion], and only for
 * a live node: none or several acceptors of the node's kind refuse it (`illegal_target`) rather than let CDI bean
 * order pick one, and a trashed target is `node_not_found` before any application sees it. The bean set is pinned
 * over the real registration; the dispatch is pinned with forged acceptors that record what they were offered and
 * answer a code no real one does, each negative beside a positive control on the same node.
 *
 * **Split** (`src/backend/CLAUDE.md`: a test lives where what it exercises runs): the methods that run with core alone are `UploadCompletionPartitionCoreSliceTest` in `:core`.
 */
@QuarkusTest
class UploadCompletionPartitionSliceTest {

    @Inject
    @All
    lateinit var completions: MutableList<UploadCompletion>

    @Inject
    lateinit var nodes: NodesStore

    /** An acceptor of [kind] that records each node it is offered and answers `forged`. */
    private class Forged(private val kind: String) : UploadCompletion {
        val offered = mutableListOf<String>()
        override fun accepts(nodeKind: String): Boolean = nodeKind == kind
        override fun complete(scopeId: String, node: NodeEntity, item: CompleteItem, opener: UploadOpener, admitted: () -> Boolean, attached: (String) -> Unit): CompleteItemResult {
            offered += node.id
            return CompleteItemResult(null, null, "forged")
        }
    }

    @Test
    fun theRegisteredCompletionsPartitionTheNodeKinds() {
        assertTrue(completions.isNotEmpty(), "the media application registers a completion")
        for (kind in listOf(NodeEntity.FOLDER, MemoriesNodeKinds.MEMORY, MemoriesNodeKinds.CHAPTER)) {
            val claiming = completions.filter { it.accepts(kind) }.map { it.javaClass.simpleName }
            assertTrue(claiming.size <= 1, "node kind $kind is accepted by more than one UploadCompletion: $claiming")
        }
        assertEquals(1, completions.count { it.accepts(MemoriesNodeKinds.MEMORY) }, "a Memory takes uploads")
        assertEquals(1, completions.count { it.accepts(MemoriesNodeKinds.CHAPTER) }, "a Chapter takes uploads")
        assertEquals(0, completions.count { it.accepts(NodeEntity.FOLDER) }, "a folder takes none")
    }

    @Test
    fun aKindNoApplicationAcceptsIsRefused() {
        val (scopeId, memory) = memory()
        val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        assertEquals("illegal_target", completeOne(completions, scopeId, folder), "the registered set accepts no folder")
        assertEquals("illegal_target", completeOne(mutableListOf(), scopeId, memory), "no acceptor at all")

        // control: the registered set does reach an acceptor for the Memory — which then refuses the unknown upload
        assertEquals("upload_not_found", completeOne(completions, scopeId, memory))
    }

    private fun memory(): Pair<String, String> {
        val scopeId = seedMemberScope().scopeId
        return scopeId to seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
    }

    /** Dispatch one item for an unknown upload to [nodeId] through a dispatcher over [acceptors]; its error code. */
    private fun completeOne(acceptors: MutableList<UploadCompletion>, scopeId: String, nodeId: String): String? {
        val item = CompleteItem(UUID.randomUUID().toString(), nodeId, UUID.randomUUID().toString(), emptyList(), mapOf(MediaUploadCompletion.NODE_EPOCH to "0"))
        return QuarkusTransaction.requiringNew().call {
            UploadCompletions(nodes, acceptors).complete(scopeId, listOf(item), UploadOpener.Member(UUID.randomUUID().toString()))
        }.single().error
    }
}
