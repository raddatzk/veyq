package me.raddatz.veyq.core.content

import me.raddatz.veyq.core.blob.AttachOutcome
import me.raddatz.veyq.core.blob.AttachmentStore
import me.raddatz.veyq.core.blob.UploadSessionEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

/**
 * The cover's two writes, each ONE transaction: the slot ([AttachmentStore], which takes the scope lock first) and the
 * node's sealed meta that names it ([NodesStore.applyUpdate], revision-checked, joining this transaction). Either both
 * land or neither does, so a cover and the meta that names it can never part - a crash or a refusal between them leaves
 * the OLD cover, meta and blob exactly as they were (inbox #411).
 *
 * **Order: the slot first, then the meta.** The slot's compare-and-set can miss ([NodeCoverOutcome.CoverStale]); that
 * outcome COMMITS (the loser's upload is spent and its blob freed, as a Depot replace is). The meta's can miss too
 * ([NodeCoverOutcome.StaleMeta]); that one rolls the whole transaction back - the attach, its freed predecessor and the
 * spent session included - so the upload is still open for the client's retry on the fresh meta (ADR 0077). Lock order
 * is the tree's: the scope row first (inside [AttachmentStore]), then the node row (the meta UPDATE).
 *
 * The scope's tree revision moves for each of the two writes (ADR 0081): two bumps for one request, harmless - the
 * revision is a change signal, not a count.
 */
@ApplicationScoped
class NodeCoverStore(private val attachments: AttachmentStore, private val nodes: NodesStore) {

    @Transactional
    fun set(
        scopeId: String, nodeId: String, kind: String, session: UploadSessionEntity, manifest: List<Long>?,
        expectedBlobId: String?, wrappedMeta: String, metaRevision: Long,
    ): NodeCoverOutcome {
        when (val o = attachments.attach(scopeId, nodeId, setOf(kind), NodeCover.SLOT, session, manifest, expectedBlobId)) {
            is AttachOutcome.Attached -> Unit
            is AttachOutcome.Conflict -> return NodeCoverOutcome.CoverStale(o.currentBlobId)
            // A concurrent request spent the session first, or the node was trashed since the handler read it.
            AttachOutcome.UploadNotFound, AttachOutcome.NodeNotFound -> return NodeCoverOutcome.NotFound
            AttachOutcome.IllegalTarget -> return NodeCoverOutcome.IllegalTarget
            AttachOutcome.UploadMismatch -> return NodeCoverOutcome.UploadMismatch
            AttachOutcome.NotAdmitted -> error("a member's cover passes no gate: $o")
        }
        return writeMeta(scopeId, nodeId, wrappedMeta, metaRevision)
    }

    @Transactional
    fun remove(scopeId: String, nodeId: String, blobId: String, wrappedMeta: String, metaRevision: Long): NodeCoverOutcome {
        when (attachments.detach(scopeId, nodeId, NodeCover.SLOT, blobId)) {
            // An empty slot (a replay, a meta that named a blob already gone) still writes the meta: the removal is idempotent.
            AttachmentStore.DetachOutcome.Detached, AttachmentStore.DetachOutcome.Empty -> Unit
            AttachmentStore.DetachOutcome.Replaced -> return NodeCoverOutcome.CoverStale(attachments.slotBlobId(nodeId, NodeCover.SLOT))
        }
        return writeMeta(scopeId, nodeId, wrappedMeta, metaRevision)
    }

    private fun writeMeta(scopeId: String, nodeId: String, wrappedMeta: String, metaRevision: Long): NodeCoverOutcome =
        when (val r = nodes.applyUpdate(scopeId, nodeId, null, null, wrappedMeta, metaRevision)) {
            NodesStore.ApplyResult.Applied -> NodeCoverOutcome.Done
            NodesStore.ApplyResult.Missing -> rolledBack(NodeCoverOutcome.NotFound)
            is NodesStore.ApplyResult.StaleMeta -> rolledBack(NodeCoverOutcome.StaleMeta(r.wrappedMeta, r.metaRevision))
            else -> error("a meta-only update answers applied, missing or stale: $r")
        }

    /** Undo the slot write above with it: the meta did not land, so neither may the cover. */
    private fun rolledBack(outcome: NodeCoverOutcome): NodeCoverOutcome {
        QuarkusTransaction.setRollbackOnly()
        return outcome
    }
}
