package me.raddatz.veyq.core.blob

import me.raddatz.veyq.core.node.NodeSubtreeEraser
import jakarta.enterprise.context.ApplicationScoped

/**
 * The attachments of an erased node subtree — a Memory's cover among them (inbox #341) — and their blobs. Phase 200,
 * before `NodeSubtreePurge` deletes the node rows, which `fk_attachment_node` refuses while an attachment still names
 * one (deliberately without a cascade).
 *
 * - The guest identities are read FIRST, while the rows still name them; the caller drops the ones nothing
 *   references any more (`AttachmentGuestIdentityUsage` answers for this table).
 * - The rows go before their blobs (`fk_attachment_blob`), and each blob through `BlobUploadStore.deleteBlobRow`,
 *   never a bare delete: the reclaim record of its store objects and the row are one step in this transaction.
 * - No row lock of its own: the caller holds the scope row's lock, which every writer of this table takes first.
 */
@ApplicationScoped
class AttachmentNodeSubtreeEraser(
    private val blobs: BlobUploadStore,
) : NodeSubtreeEraser {
    override val phase = 200

    override fun erase(scopeId: String, nodeIds: List<String>): Set<String> {
        val attachments = NodeAttachmentEntity.find("scopeId = ?1 and nodeId in ?2", scopeId, nodeIds).list()
        if (attachments.isEmpty()) return emptySet()
        val guests = attachments.mapNotNull { it.shareIdentityId }.toSet()
        NodeAttachmentEntity.delete("scopeId = ?1 and nodeId in ?2", scopeId, nodeIds)
        blobs.deleteBlobRows(scopeId, attachments.map { it.blobId })
        return guests
    }

    /** The attachments' blobs: no row lock of its own (the scope lock, above). */
    override fun blobIdsOf(scopeId: String, nodeIds: List<String>): Collection<String> =
        NodeAttachmentEntity.find("scopeId = ?1 and nodeId in ?2", scopeId, nodeIds).list().map { it.blobId }
}
