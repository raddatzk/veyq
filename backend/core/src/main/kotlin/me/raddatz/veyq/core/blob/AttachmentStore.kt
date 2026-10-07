package me.raddatz.veyq.core.blob

import me.raddatz.veyq.core.content.NodesStore
import me.raddatz.veyq.core.node.NodeEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.UUID

/** What [AttachmentStore.attach] did. Every outcome but [Attached] and [Conflict] wrote nothing. */
sealed interface AttachOutcome {
    /** The upload is now the slot's blob [blobId]; [replacedBlobId] is the blob it replaced (freed), null into an empty slot. */
    data class Attached(val blobId: String, val replacedBlobId: String?) : AttachOutcome

    /**
     * The slot did not hold what the caller expected (ADR 0077: a write carries the version it read) — an occupied
     * slot for a first attach, another blob (or none) for a replace. The attachment is unchanged and [currentBlobId] is
     * what the slot holds now (null: empty). The upload is spent and its blob freed — reclaimed, never attached.
     */
    data class Conflict(val currentBlobId: String?) : AttachOutcome

    /** The caller's gate refused when asked again under the scope lock (a guest's link revoked, its target moved out). */
    object NotAdmitted : AttachOutcome

    /** The session is gone: spent by a complete that committed first, or by an earlier one (a replay). */
    object UploadNotFound : AttachOutcome

    /** The session's wrap is not sealed for this node and slot (ADR 0072): attached here, nobody could open it. */
    object UploadMismatch : AttachOutcome

    /** No live node [AttachmentStore.attach] was asked for in this scope — never was, another scope's, or trashed. */
    object NodeNotFound : AttachOutcome

    /** The node is live, but not of a kind the caller attaches to. */
    object IllegalTarget : AttachOutcome
}

/**
 * Core's write path for node attachments (ADR 0079 Consequences, cr-files-application P3): a verified upload becomes
 * the blob in one slot of a node ([NodeAttachmentEntity]), first or as a replacement. Operator-blind: the session's
 * `K_blob` wrap (sealed under the node's key, ADR 0080 Decision 13) moves onto the blob row as it is; nothing is opened.
 *
 * **How an application uses it.** Its [UploadCompletion.complete] resolves the session for the opener
 * ([BlobUploadStore.findSession]), verifies the stored chunks ([BlobUploadHandler.verifyBlob] — on `Budget` it purges
 * and answers as today), and then calls [attach] with the node core resolved, the kinds it attaches to, its slot token,
 * the expected blob it carries in its own [CompleteItem.payloads] (a replace) and the `admitted` gate it was handed. It
 * maps the outcome onto its [CompleteItemResult]: [AttachOutcome.Attached] → the blob id, the failures onto its own
 * codes (`UploadCompletions` already names `node_not_found` and `illegal_target`; Memories names `upload_not_found` and
 * `upload_mismatch`). A [AttachOutcome.Conflict] is the application's to project — its contract names that code.
 */
@ApplicationScoped
class AttachmentStore(private val nodes: NodesStore, private val blobs: BlobUploadStore) {

    /**
     * Attach the verified [session] as the blob in [slot] of [nodeId] — a live node of [scopeId] of one of [kinds] — in
     * ONE transaction, the caller's if it has one. [expectedBlobId] is the blob the caller saw in the slot: null for a
     * first attach into an empty slot, else the blob a replace replaces. [manifest] is [BlobUploadHandler.verifyBlob]'s.
     * The uploader recorded on the attachment is the session's own opener (a guest identity, or null for a member).
     *
     * **Order** (each step decided where it writes, developer-styleguide §12):
     *  1. the scope row's lock ([NodesStore.lockScope]) — the lock every tree writer and both attachment erasers take
     *     first, so a trash, a purge or a peer's attach of this node has committed and is seen (READ COMMITTED, ADR
     *     0078) or waits until this one is done; under it a concurrent insert into the slot cannot interleave;
     *  2. [admitted] — the caller's gate asked again, AFTER the scope lock: a guest's gate takes the share row, and the
     *     order is scope → share, never the reverse (a re-activation holds the scope and waits for the share row,
     *     `ShareNodesHandler`); a member passes none;
     *  3. the session spent — the transaction's first write, its row count deciding: a second attach of one upload
     *     answers [AttachOutcome.UploadNotFound] as a replay does, never a 500 at the blob's primary key;
     *  4. the node live, in the scope and of a kind of [kinds] — by a QUERY under the lock, never `findById`: an entity
     *     this transaction loaded before the lock (`UploadCompletions` resolves the node first; a caller that holds a
     *     transaction keeps it) is handed back unchanged, so a trash committed while this waited would not be seen;
     *  5. the blob row ([BlobUploadStore.persistBlob]), flushed, so the slot's foreign key finds it;
     *  6. the swap — into an empty slot an insert (`ux_attachment_node_slot` backs it), a replace a compare-and-set
     *     `… where blob_id = expected`; a miss is a [AttachOutcome.Conflict] that frees the new blob in this transaction;
     *  7. the replaced blob freed ([BlobUploadStore.deleteBlobRow]: row + reclaim record, never a bare delete — there
     *     is no sweep) in this transaction.
     * Every refusal before the swap rolls the transaction back, the spend included: the session stays open and expires
     * as an unfinished upload does (`UploadSessionPurge` reclaims its objects).
     */
    @Transactional
    fun attach(
        scopeId: String,
        nodeId: String,
        kinds: Set<String>,
        slot: String,
        session: UploadSessionEntity,
        manifest: List<Long>?,
        expectedBlobId: String?,
        admitted: () -> Boolean = { true },
    ): AttachOutcome {
        require(SLOT.matches(slot)) { "a slot is an application's token ${SLOT.pattern}" }
        require(session.scopeId == scopeId) { "the session is resolved in the scope it is attached in (findSession)" }
        if (!session.boundTo(nodeId, slot)) return AttachOutcome.UploadMismatch

        nodes.lockScope(scopeId)
        if (!admitted()) return refused(AttachOutcome.NotAdmitted)
        if (UploadSessionEntity.delete("id = ?1", session.id) != 1L) return refused(AttachOutcome.UploadNotFound)
        val kind = liveKind(scopeId, nodeId) ?: return refused(AttachOutcome.NodeNotFound)
        if (kind !in kinds) return refused(AttachOutcome.IllegalTarget)

        val blobId = blobs.persistBlob(session, manifest)
        BlobEntity.flush()
        val swapped = if (expectedBlobId == null) {
            insertIntoEmpty(scopeId, nodeId, slot, blobId, session.openerShareIdentityId)
        } else {
            swapExpected(nodeId, slot, expectedBlobId, blobId, session.openerShareIdentityId)
        }
        if (!swapped) {
            blobs.deleteBlobRow(blobId) // the loser's upload is freed, never left attached to nothing
            return AttachOutcome.Conflict(currentBlobId(nodeId, slot))
        }
        blobs.deleteBlobRow(expectedBlobId)
        return AttachOutcome.Attached(blobId, expectedBlobId)
    }

    /** An insert, only while the slot is empty — read under the scope lock every writer of the table holds. */
    private fun insertIntoEmpty(scopeId: String, nodeId: String, slot: String, blobId: String, shareIdentityId: String?): Boolean {
        if (currentBlobId(nodeId, slot) != null) return false
        NodeAttachmentEntity().apply {
            id = UUID.randomUUID().toString()
            this.scopeId = scopeId
            this.nodeId = nodeId
            this.slot = slot
            this.blobId = blobId
            this.shareIdentityId = shareIdentityId
            createdAt = Instant.now()
        }.persist()
        return true
    }

    /**
     * The replace: a compare-and-set whose predicate is the expected blob — a bulk UPDATE, so it reads the row as
     * committed when it writes, never a copy loaded before. The uploader moves with the blob. Panache binds no null
     * parameter, so a member's (null) identity is spelled into the statement rather than bound.
     */
    private fun swapExpected(nodeId: String, slot: String, expectedBlobId: String, blobId: String, shareIdentityId: String?): Boolean {
        val guard = "where nodeId = ?2 and slot = ?3 and blobId = ?4"
        val rows = if (shareIdentityId == null) {
            NodeAttachmentEntity.update("blobId = ?1, shareIdentityId = null $guard", blobId, nodeId, slot, expectedBlobId)
        } else {
            NodeAttachmentEntity.update("blobId = ?1, shareIdentityId = ?5 $guard", blobId, nodeId, slot, expectedBlobId, shareIdentityId)
        }
        return rows == 1
    }

    /** The node's kind when it is live in [scopeId], read from the database (a scalar query is never a cached entity). */
    private fun liveKind(scopeId: String, nodeId: String): String? =
        NodeEntity.getEntityManager()
            .createQuery("select n.kind from NodeEntity n where n.id = ?1 and n.scopeId = ?2 and n.deletedAt is null", String::class.java)
            .setParameter(1, nodeId).setParameter(2, scopeId)
            .resultList.singleOrNull()

    /** The blob the slot holds now, read from the database for the reason [liveKind] is. */
    private fun currentBlobId(nodeId: String, slot: String): String? =
        NodeAttachmentEntity.getEntityManager()
            .createQuery("select a.blobId from NodeAttachmentEntity a where a.nodeId = ?1 and a.slot = ?2", String::class.java)
            .setParameter(1, nodeId).setParameter(2, slot)
            .resultList.singleOrNull()

    private fun refused(outcome: AttachOutcome): AttachOutcome {
        QuarkusTransaction.setRollbackOnly() // the spend, if it happened, is undone with everything else
        return outcome
    }

    private companion object {
        /** A slot is a role token (`BlobRole`, `_common.openapi.yaml`): the same anchored shape, one token space. */
        val SLOT = Regex("^[A-Z][A-Z0-9_]{0,15}$")
    }
}
