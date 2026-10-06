package me.raddatz.veyq.core.blob

import me.raddatz.veyq.core.content.NodesStore
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * The completion seam is **application-agnostic** (ADR 0079, cr-platform-contract-split D8): core's dispatcher
 * ([UploadCompletions]) hands an item of a kind no Memories code knows — a made-up `EXPENSE` with an application's
 * own derivative token and sealed payload — to the one implementation that claims the kind **unchanged**, and returns
 * that implementation's result as it is. Core reads the item's node and nothing else of it.
 *
 * The double is not a CDI bean (`:core`'s test application holds no implementation — `CoreAloneSliceTest`); it goes
 * into a dispatcher built over it, as `UploadCompletionPartitionCoreSliceTest`'s forged acceptors do.
 */
@QuarkusTest
class UploadCompletionGenericItemCoreSliceTest {

    @Inject
    lateinit var nodes: NodesStore

    /** A second application's completion for [EXPENSE]: records what it is handed and answers [answer]. */
    private class ExpenseCompletion(val answer: CompleteItemResult) : UploadCompletion {
        val received = mutableListOf<CompleteItem>()
        override fun accepts(nodeKind: String): Boolean = nodeKind == EXPENSE
        override fun complete(scopeId: String, node: NodeEntity, item: CompleteItem, opener: UploadOpener, admitted: () -> Boolean): CompleteItemResult {
            assertEquals(item.nodeId, node.id, "the implementation is handed the item's own node")
            received += item
            return answer
        }
    }

    @Test
    fun aNonMemoriesItemReachesItsImplementationUnchangedAndItsResultComesBack() {
        val scopeId = seedMemberScope().scopeId
        val nodeId = seedNode(scopeId, EXPENSE, wrappedContentKey = CONTENT_KEY)
        val ownerId = UUID.randomUUID().toString()
        val item = CompleteItem(
            ownerId = ownerId,
            nodeId = nodeId,
            originalUploadId = UUID.randomUUID().toString(),
            derivatives = listOf(DerivativeDeclare("RECEIPT_SCAN", UUID.randomUUID().toString())),
            payloads = mapOf("sealedEntry" to "c2VhbGVk", "sealedCategory" to "Y2F0"),
        )
        val answer = CompleteItemResult(UUID.randomUUID().toString(), ownerId, null)
        val expense = ExpenseCompletion(answer)

        val results = QuarkusTransaction.requiringNew().call {
            UploadCompletions(nodes, mutableListOf(expense)).complete(scopeId, listOf(item), UploadOpener.Member(UUID.randomUUID().toString()))
        }

        assertEquals(listOf(item), expense.received, "the item reaches the claiming implementation exactly as built")
        assertSame(answer, results.single(), "the dispatcher returns the implementation's own result")
    }

    private companion object {
        /** A kind no application in this repo declares — the seam must not care. */
        const val EXPENSE = "EXPENSE"
    }
}
