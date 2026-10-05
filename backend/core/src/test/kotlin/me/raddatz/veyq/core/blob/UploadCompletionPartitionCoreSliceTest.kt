package me.raddatz.veyq.core.blob

import me.raddatz.veyq.core.content.NodesStore
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Core's completion dispatcher ([UploadCompletions]) offers an item to **exactly one** [UploadCompletion], and only for
 * a live node: none or several acceptors of the node's kind refuse it (`illegal_target`) rather than let CDI bean
 * order pick one, and a trashed target is `node_not_found` before any application sees it. The bean set is pinned
 * over the real registration; the dispatch is pinned with forged acceptors that record what they were offered and
 * answer a code no real one does, each negative beside a positive control on the same node.
 *
 * **Split from `UploadCompletionPartitionSliceTest`** (cr-platform-gradle-modules step 8a): these methods run with core alone, so they live in
 * `:core`; the rest of the class is `UploadCompletionPartitionSliceTest` in `:apps:memories`. The bodies moved verbatim,
 * but for `MemoriesNodeKinds.MEMORY` (a Memories class `:core` cannot import) spelled as its wire literal `"MEMORY"`.
 */
@QuarkusTest
class UploadCompletionPartitionCoreSliceTest {

    @Inject
    lateinit var nodes: NodesStore

    /** An acceptor of [kind] that records each node it is offered and answers `forged`. */
    private class Forged(private val kind: String) : UploadCompletion {
        val offered = mutableListOf<String>()
        override fun accepts(nodeKind: String): Boolean = nodeKind == kind
        override fun complete(scopeId: String, node: NodeEntity, item: CompleteItem, opener: UploadOpener, admitted: () -> Boolean): CompleteItemResult {
            offered += node.id
            return CompleteItemResult(null, null, "forged")
        }
    }

    @Test
    fun twoAcceptorsOfOneKindRefuseTheItemAndNeitherIsOffered() {
        val (scopeId, memory) = memory()
        val a = Forged("MEMORY")
        val b = Forged("MEMORY")
        assertEquals("illegal_target", completeOne(mutableListOf(a, b), scopeId, memory))
        assertTrue(a.offered.isEmpty() && b.offered.isEmpty(), "an ambiguous kind is offered to nobody")

        // control: either of them alone is offered the item
        assertEquals("forged", completeOne(mutableListOf(a), scopeId, memory))
        assertEquals(listOf(memory), a.offered)
    }

    @Test
    fun aTrashedTargetIsNodeNotFoundAndNeverOffered() {
        val (scopeId, memory) = memory()
        val forged = Forged("MEMORY")
        assertEquals("forged", completeOne(mutableListOf(forged), scopeId, memory)) // control: the live node is offered

        QuarkusTransaction.requiringNew().run { NodeEntity.findById(memory)!!.deletedAt = Instant.now() }
        assertEquals("node_not_found", completeOne(mutableListOf(forged), scopeId, memory))
        assertEquals(listOf(memory), forged.offered, "the trashed node was never offered")
    }

    private fun memory(): Pair<String, String> {
        val scopeId = seedMemberScope().scopeId
        return scopeId to seedNode(scopeId, "MEMORY", wrappedContentKey = CONTENT_KEY)
    }

    /** Dispatch one item for an unknown upload to [nodeId] through a dispatcher over [acceptors]; its error code. */
    private fun completeOne(acceptors: MutableList<UploadCompletion>, scopeId: String, nodeId: String): String? {
        val item = CompleteItem(UUID.randomUUID().toString(), nodeId, UUID.randomUUID().toString(), emptyList(), null, null)
        return QuarkusTransaction.requiringNew().call {
            UploadCompletions(nodes, acceptors).complete(scopeId, listOf(item), UploadOpener.Member(UUID.randomUUID().toString()))
        }.single().error
    }
}
