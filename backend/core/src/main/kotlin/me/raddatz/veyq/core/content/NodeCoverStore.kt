package me.raddatz.veyq.core.content

import me.raddatz.veyq.core.blob.AttachOutcome
import me.raddatz.veyq.core.blob.AttachmentStore
import me.raddatz.veyq.core.blob.SharedBlobSpec
import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.identity.PrincipalKey
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
        expectedBlobId: String?, wrappedMeta: String, metaRevision: Long, editor: PrincipalKey,
    ): NodeCoverOutcome {
        val o = attachments.attach(scopeId, nodeId, setOf(kind), NodeCover.SLOT, session, manifest, expectedBlobId)
        return refusal(o) ?: writeMeta(scopeId, nodeId, wrappedMeta, metaRevision, editor)
    }

    /**
     * [set] with a new row on an object the node already stores in place of an upload (ADR 0013 § 5): the picked image
     * was found equal to a medium's PREVIEW, so nothing was uploaded. [memberUserId] is the attachment's uploader. A slot
     * miss commits as [set]'s does (the new row freed, the source's object kept: another row names it).
     */
    @Transactional
    fun setShared(
        scopeId: String, nodeId: String, kind: String, shared: SharedBlobSpec, memberUserId: String,
        expectedBlobId: String?, wrappedMeta: String, metaRevision: Long, editor: PrincipalKey,
    ): NodeCoverOutcome {
        val o = attachments.attachShared(scopeId, nodeId, setOf(kind), NodeCover.SLOT, shared, memberUserId, expectedBlobId)
        return refusal(o) ?: writeMeta(scopeId, nodeId, wrappedMeta, metaRevision, editor)
    }

    /** The outcome of the slot write, or null when it attached and the meta is next. */
    private fun refusal(o: AttachOutcome): NodeCoverOutcome? = when (o) {
        is AttachOutcome.Attached -> null
        is AttachOutcome.Conflict -> NodeCoverOutcome.CoverStale(o.currentBlobId)
        // A concurrent request spent the session first, or the node was trashed since the handler read it.
        AttachOutcome.UploadNotFound, AttachOutcome.NodeNotFound -> NodeCoverOutcome.NotFound
        AttachOutcome.IllegalTarget -> NodeCoverOutcome.IllegalTarget
        AttachOutcome.UploadMismatch -> NodeCoverOutcome.UploadMismatch
        AttachOutcome.SharedSourceInvalid -> NodeCoverOutcome.SharedSourceInvalid
        AttachOutcome.NotAdmitted -> error("a member's cover passes no gate: $o")
    }

    @Transactional
    fun remove(scopeId: String, nodeId: String, blobId: String, wrappedMeta: String, metaRevision: Long, editor: PrincipalKey): NodeCoverOutcome {
        when (attachments.detach(scopeId, nodeId, NodeCover.SLOT, blobId)) {
            // An empty slot (a replay, a meta that named a blob already gone) still writes the meta: the removal is idempotent.
            AttachmentStore.DetachOutcome.Detached, AttachmentStore.DetachOutcome.Empty -> Unit
            AttachmentStore.DetachOutcome.Replaced -> return NodeCoverOutcome.CoverStale(attachments.slotBlobId(nodeId, NodeCover.SLOT))
        }
        return writeMeta(scopeId, nodeId, wrappedMeta, metaRevision, editor)
    }

    /** The node's re-sealed meta, with [editor] — the member who set or removed the cover — as its editor. */
    private fun writeMeta(scopeId: String, nodeId: String, wrappedMeta: String, metaRevision: Long, editor: PrincipalKey): NodeCoverOutcome =
        when (val r = nodes.applyUpdate(scopeId, nodeId, null, null, wrappedMeta, metaRevision, editor)) {
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
