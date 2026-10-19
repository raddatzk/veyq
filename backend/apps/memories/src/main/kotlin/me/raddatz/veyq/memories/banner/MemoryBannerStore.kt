package me.raddatz.veyq.memories.banner

import me.raddatz.veyq.core.blob.AttachOutcome
import me.raddatz.veyq.core.blob.AttachmentStore
import me.raddatz.veyq.core.blob.SharedBlobSpec
import me.raddatz.veyq.core.blob.StoredObjectLocks
import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.content.NodesStore
import me.raddatz.veyq.core.identity.PrincipalKey
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

/**
 * The banner's two writes, each ONE transaction: both slots ([AttachmentStore]) and the node's sealed meta that names
 * them ([NodesStore.applyUpdate], revision-checked, joining this transaction). Either all land or none does, so the
 * banner's bytes and the meta that names them can never part (ADR 0102 decision 5).
 *
 * **Unlike the cover, a stale slot writes nothing.** A cover has one slot, so its compare-and-set miss commits (the loser's
 * upload spent, as a Depot replace is). The banner has two, and a miss on the second after the first was swapped would
 * leave a banner whose image and poster disagree with the meta. So both slots are compared FIRST, under the scope row's
 * lock - the lock [AttachmentStore] takes again and every writer of the attachment table holds - and a miss is
 * [MemoryBannerOutcome.BannerStale] before anything is written; the uploads stay open for a retry, or for the stale-session
 * purge. Under that lock no peer can move a slot between the compare and the swaps, so a swap's own miss cannot happen;
 * were it to, the whole transaction rolls back to the same answer. A meta that moved rolls everything back too
 * ([MemoryBannerOutcome.StaleMeta]), the cover's rule (ADR 0077).
 *
 * Lock order is the tree's: the scope row first, then the node row (the meta UPDATE). The tree revision moves once per
 * slot write and once for the meta (ADR 0081) - a change signal, not a count.
 */
@ApplicationScoped
class MemoryBannerStore(private val attachments: AttachmentStore, private val nodes: NodesStore) {

    /** What fills one slot, and the blob the caller read there (null: empty). */
    sealed interface Fill {
        val expectedBlobId: String?
    }

    /** One verified upload for one slot. */
    data class Upload(val session: UploadSessionEntity, val manifest: List<Long>?, override val expectedBlobId: String?) : Fill

    /**
     * A new row on an object the node already stores, in place of an upload (ADR 0013 § 5): the picked file was found
     * equal to a medium's PREVIEW (an image, a video's poster) or ORIGINAL (a video). [memberUserId] is its uploader.
     */
    data class Shared(val spec: SharedBlobSpec, val memberUserId: String, override val expectedBlobId: String?) : Fill

    /**
     * Attach [banner] to the banner slot and [poster] to the poster slot (each an upload or a shared row) - or, with no [poster], empty the poster slot of
     * [expectedPosterBlobId] (an image replacing a video) - and write [wrappedMeta] at [metaRevision].
     */
    @Transactional
    fun set(
        scopeId: String, nodeId: String, banner: Fill, poster: Fill?, expectedPosterBlobId: String?,
        wrappedMeta: String, metaRevision: Long, editor: PrincipalKey,
    ): MemoryBannerOutcome {
        nodes.lockScope(scopeId)
        val current = slots(nodeId)
        if (current != (banner.expectedBlobId to expectedPosterBlobId)) return MemoryBannerOutcome.BannerStale(current.first, current.second)
        // Every object both slots touch - the predecessors and the shared sources - locked at once, in the one lock
        // order (StoredObjectLocks): slot by slot, they would be locked crosswise to a measure-taker's.
        StoredObjectLocks.lockOf(
            scopeId,
            listOf(banner.expectedBlobId, expectedPosterBlobId, (banner as? Shared)?.spec?.sourceBlobId, (poster as? Shared)?.spec?.sourceBlobId),
        )

        attach(scopeId, nodeId, MemoryBanner.SLOT, banner)?.let { return it }
        if (poster != null) {
            attach(scopeId, nodeId, MemoryBanner.POSTER_SLOT, poster)?.let { return it }
        } else if (expectedPosterBlobId != null) {
            // An image replaces a video: its poster goes with it, freed in this transaction (row + reclaim record).
            when (attachments.detach(scopeId, nodeId, MemoryBanner.POSTER_SLOT, expectedPosterBlobId)) {
                AttachmentStore.DetachOutcome.Detached -> Unit
                AttachmentStore.DetachOutcome.Empty, AttachmentStore.DetachOutcome.Replaced -> return rolledBack(stale(nodeId))
            }
        }
        return writeMeta(scopeId, nodeId, wrappedMeta, metaRevision, editor)
    }

    /**
     * Empty both slots - each only while it holds the blob stated ([blobId], [posterBlobId]) or nothing - and write
     * [wrappedMeta] at [metaRevision]. An empty slot (a replay, a meta naming a blob already gone) still writes the
     * meta: the removal is idempotent.
     */
    @Transactional
    fun remove(
        scopeId: String, nodeId: String, blobId: String, posterBlobId: String?, wrappedMeta: String, metaRevision: Long, editor: PrincipalKey,
    ): MemoryBannerOutcome {
        nodes.lockScope(scopeId)
        val (bannerNow, posterNow) = slots(nodeId)
        if ((bannerNow != null && bannerNow != blobId) || (posterNow != null && posterNow != posterBlobId)) {
            return MemoryBannerOutcome.BannerStale(bannerNow, posterNow)
        }
        StoredObjectLocks.lockOf(scopeId, listOf(bannerNow, posterNow)) // both objects at once, in the one lock order
        if (bannerNow != null) detachHeld(scopeId, nodeId, MemoryBanner.SLOT, bannerNow)?.let { return it }
        if (posterNow != null) detachHeld(scopeId, nodeId, MemoryBanner.POSTER_SLOT, posterNow)?.let { return it }
        return writeMeta(scopeId, nodeId, wrappedMeta, metaRevision, editor)
    }

    /** The blobs the two slots hold now, read under the scope lock. */
    private fun slots(nodeId: String): Pair<String?, String?> =
        attachments.slotBlobId(nodeId, MemoryBanner.SLOT) to attachments.slotBlobId(nodeId, MemoryBanner.POSTER_SLOT)

    private fun stale(nodeId: String): MemoryBannerOutcome = slots(nodeId).let { MemoryBannerOutcome.BannerStale(it.first, it.second) }

    /** Attach one fill, or the refusal - every refusal after a write rolls back the whole transaction. */
    private fun attach(scopeId: String, nodeId: String, slot: String, fill: Fill): MemoryBannerOutcome? =
        when (
            when (fill) {
                is Upload -> attachments.attach(scopeId, nodeId, MemoryBanner.KINDS, slot, fill.session, fill.manifest, fill.expectedBlobId)
                is Shared -> attachments.attachShared(scopeId, nodeId, MemoryBanner.KINDS, slot, fill.spec, fill.memberUserId, fill.expectedBlobId)
            }
        ) {
            is AttachOutcome.Attached -> null
            // The scope lock held since the compare makes this unreachable; a miss still writes nothing.
            is AttachOutcome.Conflict -> rolledBack(stale(nodeId))
            // A concurrent request spent the session first, or the node was trashed since the handler read it. Core's
            // refusals have set the rollback already.
            AttachOutcome.UploadNotFound, AttachOutcome.NodeNotFound -> rolledBack(MemoryBannerOutcome.NotFound)
            AttachOutcome.IllegalTarget -> rolledBack(MemoryBannerOutcome.IllegalTarget)
            AttachOutcome.UploadMismatch -> rolledBack(MemoryBannerOutcome.UploadMismatch)
            AttachOutcome.SharedSourceInvalid -> rolledBack(MemoryBannerOutcome.SharedSourceInvalid)
            AttachOutcome.NotAdmitted -> error("a member's banner passes no gate")
        }

    private fun detachHeld(scopeId: String, nodeId: String, slot: String, held: String): MemoryBannerOutcome? =
        when (attachments.detach(scopeId, nodeId, slot, held)) {
            AttachmentStore.DetachOutcome.Detached -> null
            // Read under the same lock a moment ago: unreachable, and nothing is kept if it happens.
            AttachmentStore.DetachOutcome.Empty, AttachmentStore.DetachOutcome.Replaced -> rolledBack(stale(nodeId))
        }

    /** The node's re-sealed meta, with [editor] — the member who set or removed the banner — as its editor. */
    private fun writeMeta(scopeId: String, nodeId: String, wrappedMeta: String, metaRevision: Long, editor: PrincipalKey): MemoryBannerOutcome =
        when (val r = nodes.applyUpdate(scopeId, nodeId, null, null, wrappedMeta, metaRevision, editor)) {
            NodesStore.ApplyResult.Applied -> MemoryBannerOutcome.Done
            NodesStore.ApplyResult.Missing -> rolledBack(MemoryBannerOutcome.NotFound)
            is NodesStore.ApplyResult.StaleMeta -> rolledBack(MemoryBannerOutcome.StaleMeta(r.wrappedMeta, r.metaRevision))
            else -> error("a meta-only update answers applied, missing or stale: $r")
        }

    /** Undo every slot write above: the banner and the meta that names it land together or not at all. */
    private fun rolledBack(outcome: MemoryBannerOutcome): MemoryBannerOutcome {
        QuarkusTransaction.setRollbackOnly()
        return outcome
    }
}
