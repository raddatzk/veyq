package me.raddatz.veyq.core.blob

import me.raddatz.veyq.core.content.NodesStore
import me.raddatz.veyq.core.node.NodeEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
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

    /** [AttachmentStore.attachShared]: the source is no live blob of this node, is blocked, or the new id is taken. */
    object SharedSourceInvalid : AttachOutcome
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
class AttachmentStore(private val nodes: NodesStore, private val blobs: BlobUploadStore, private val shared: SharedBlobs) {

    /**
     * Attach the verified [session] as the blob in [slot] of [nodeId] — a live node of [scopeId] of one of [kinds] — in
     * ONE transaction, the caller's if it has one. [expectedBlobId] is the blob the caller saw in the slot: null for a
     * first attach into an empty slot, else the blob a replace replaces. [manifest] is [BlobUploadHandler.verifyBlob]'s.
     * The uploader recorded on the attachment is the session's own opener — a guest identity or a member (ADR 0083
     * Decision 8): exactly one, as the session holds exactly one; a replace moves it to the replacer.
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
     *     is no sweep) in this transaction;
     *  8. the scope's tree revision moved ([NodesStore.bumpTree], ADR 0081), the last write — a refusal above bumps
     *     nothing, a [AttachOutcome.Conflict] changed no slot;
     *  9. [attached] told the node, once the swap stands — a guest's caller records the write there (ADR 0093 § 10);
     *     a refusal and a [AttachOutcome.Conflict] never tell it.
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
        attached: (objectId: String) -> Unit = {},
    ): AttachOutcome {
        require(SLOT.matches(slot)) { "a slot is an application's token ${SLOT.pattern}" }
        require(session.scopeId == scopeId) { "the session is resolved in the scope it is attached in (findSession)" }
        if (!session.boundTo(nodeId, slot)) return AttachOutcome.UploadMismatch

        nodes.lockScope(scopeId)
        if (!admitted()) return refused(AttachOutcome.NotAdmitted)
        if (UploadSessionEntity.delete("id = ?1", session.id) != 1L) return refused(AttachOutcome.UploadNotFound)
        val kind = liveKind(scopeId, nodeId) ?: return refused(AttachOutcome.NodeNotFound)
        if (kind !in kinds) return refused(AttachOutcome.IllegalTarget)

        val outcome = swapIn(scopeId, nodeId, slot, blobs.persistBlob(session, manifest), Uploader.of(session), expectedBlobId)
        if (outcome is AttachOutcome.Attached) {
            nodes.bumpTree(scopeId) // a first blob or a replacement is a change of the level view (a file's uploaded state, a cover; ADR 0081)
            attached(nodeId)
        }
        return outcome
    }

    /**
     * [attach] for a **shared** row (ADR 0013 § 5): the slot's new blob is a row on the stored
     * object of a blob [nodeId] already holds ([SharedBlobs.persist]), nothing uploaded. A member's write only - no gate,
     * no session - so the uploader recorded is [memberUserId]. The same order as [attach] from the scope lock on, with
     * the shared row's checks ([AttachOutcome.SharedSourceInvalid]) in place of the session's spend; a miss of the swap
     * frees the new row and leaves the source's object, which another row still names ([BlobUploadStore.deleteBlobRow]).
     */
    @Transactional
    fun attachShared(
        scopeId: String,
        nodeId: String,
        kinds: Set<String>,
        slot: String,
        spec: SharedBlobSpec,
        memberUserId: String,
        expectedBlobId: String?,
    ): AttachOutcome {
        require(SLOT.matches(slot)) { "a slot is an application's token ${SLOT.pattern}" }
        nodes.lockScope(scopeId)
        val kind = liveKind(scopeId, nodeId) ?: return refused(AttachOutcome.NodeNotFound)
        if (kind !in kinds) return refused(AttachOutcome.IllegalTarget)
        // The source's object and the predecessor's, both locked before either, in the one order (StoredObjectLocks).
        StoredObjectLocks.lockOf(scopeId, listOf(spec.sourceBlobId, expectedBlobId))
        val blobId = shared.persist(scopeId, nodeId, spec) ?: return refused(AttachOutcome.SharedSourceInvalid)
        val outcome = swapIn(scopeId, nodeId, slot, blobId, Uploader.Member(memberUserId), expectedBlobId)
        if (outcome is AttachOutcome.Attached) nodes.bumpTree(scopeId) // the level view's change, as [attach]'s
        return outcome
    }

    /** Steps 5 to 7 of [attach]: the new row flushed, the swap, the freed predecessor. The caller moves the revision. */
    private fun swapIn(
        scopeId: String, nodeId: String, slot: String, blobId: String, uploader: Uploader, expectedBlobId: String?,
    ): AttachOutcome {
        BlobEntity.flush()
        val swapped = if (expectedBlobId == null) {
            insertIntoEmpty(scopeId, nodeId, slot, blobId, uploader)
        } else {
            swapExpected(nodeId, slot, expectedBlobId, blobId, uploader)
        }
        if (!swapped) {
            blobs.deleteBlobRow(blobId) // the loser's upload is freed, never left attached to nothing
            return AttachOutcome.Conflict(currentBlobId(nodeId, slot))
        }
        blobs.deleteBlobRow(expectedBlobId)
        return AttachOutcome.Attached(blobId, expectedBlobId)
    }

    /** What [detach] did. Only [Detached] wrote. */
    enum class DetachOutcome {
        /** The slot held the expected blob: the row is gone and the blob freed (row + reclaim record). */
        Detached,

        /** The slot is empty — never filled, detached before (a replay), or the node is not live in the scope. */
        Empty,

        /** The slot holds another blob than the expected one: a peer replaced it, and that replace freed the expected blob. */
        Replaced,
    }

    /**
     * Empty [slot] of [nodeId] — a live node of [scopeId] — only while it still holds [expectedBlobId] (ADR 0077: a
     * write carries the version it read), freeing the blob in the same transaction ([BlobUploadStore.deleteBlobRow]).
     * An owner's own remove operation (core's `NodeCoverStore.remove` since inbox #411, Memories' `deleteCoverBlob` before it); the erasers empty slots with
     * their node, not through here.
     *
     * Under the scope row's lock, as [attach] and both erasers take it first, so a peer's attach of this slot has
     * committed and is seen, or waits. The compare is the delete's own predicate, never a read before it. A node that
     * is not live answers [DetachOutcome.Empty] and frees nothing: a trashed node keeps its attachments for a restore,
     * and the purge frees them (`AttachmentNodeSubtreeEraser`).
     */
    @Transactional
    fun detach(scopeId: String, nodeId: String, slot: String, expectedBlobId: String): DetachOutcome {
        require(SLOT.matches(slot)) { "a slot is an application's token ${SLOT.pattern}" }
        nodes.lockScope(scopeId)
        if (liveKind(scopeId, nodeId) == null) return DetachOutcome.Empty
        val removed = NodeAttachmentEntity.delete(
            "scopeId = ?1 and nodeId = ?2 and slot = ?3 and blobId = ?4", scopeId, nodeId, slot, expectedBlobId,
        )
        if (removed == 1L) {
            blobs.deleteBlobRow(expectedBlobId)
            nodes.bumpTree(scopeId) // the slot is empty now: a change of the level view (ADR 0081)
            return DetachOutcome.Detached
        }
        return if (currentBlobId(nodeId, slot) == null) DetachOutcome.Empty else DetachOutcome.Replaced
    }

    /** The blob in [slot] of [nodeId] now, or null — what a caller passes [attach] as the expected blob of a replace. */
    fun slotBlobId(nodeId: String, slot: String): String? = currentBlobId(nodeId, slot)

    /** An insert, only while the slot is empty — read under the scope lock every writer of the table holds. */
    private fun insertIntoEmpty(scopeId: String, nodeId: String, slot: String, blobId: String, uploader: Uploader): Boolean {
        if (currentBlobId(nodeId, slot) != null) return false
        NodeAttachmentEntity().apply {
            id = UUID.randomUUID().toString()
            this.scopeId = scopeId
            this.nodeId = nodeId
            this.slot = slot
            this.blobId = blobId
            shareIdentityId = (uploader as? Uploader.Guest)?.shareIdentityId
            uploaderUserId = (uploader as? Uploader.Member)?.userId
        }.persist()
        return true
    }

    /**
     * The replace: a compare-and-set whose predicate is the expected blob — a bulk UPDATE, so it reads the row as
     * committed when it writes, never a copy loaded before. The uploader moves with the blob: the replacer's column is
     * set and the other one cleared. Panache binds no null parameter, so the cleared column is spelled into the
     * statement rather than bound.
     */
    private fun swapExpected(nodeId: String, slot: String, expectedBlobId: String, blobId: String, uploader: Uploader): Boolean {
        val guard = "where nodeId = ?2 and slot = ?3 and blobId = ?4"
        val rows = when (uploader) {
            is Uploader.Member -> NodeAttachmentEntity.update(
                "blobId = ?1, shareIdentityId = null, uploaderUserId = ?5 $guard", blobId, nodeId, slot, expectedBlobId, uploader.userId,
            )
            is Uploader.Guest -> NodeAttachmentEntity.update(
                "blobId = ?1, shareIdentityId = ?5, uploaderUserId = null $guard", blobId, nodeId, slot, expectedBlobId, uploader.shareIdentityId,
            )
        }
        return rows == 1
    }

    /** Who an attachment names as its uploader: the session's one opener (`ck_attachment_uploader` holds exactly one). */
    private sealed interface Uploader {
        data class Member(val userId: String) : Uploader
        data class Guest(val shareIdentityId: String) : Uploader

        companion object {
            fun of(session: UploadSessionEntity): Uploader {
                val user = session.openerUserId
                val guest = session.openerShareIdentityId
                return when {
                    user != null && guest == null -> Member(user)
                    guest != null && user == null -> Guest(guest)
                    else -> error("an upload session has exactly one opener (BlobUploadStore.createSession)")
                }
            }
        }
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
