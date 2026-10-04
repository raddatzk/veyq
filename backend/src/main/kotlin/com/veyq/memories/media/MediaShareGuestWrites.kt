package com.veyq.memories.media

import com.veyq.core.blob.BlobUploadHandler
import com.veyq.core.blob.CompleteItem
import com.veyq.core.blob.CompleteItemResult
import com.veyq.core.blob.UploadCompletions
import com.veyq.core.blob.UploadDeclare
import com.veyq.core.blob.UploadItemResult
import com.veyq.core.blob.UploadOpener
import com.veyq.core.blob.WindowItemResult
import com.veyq.core.blob.WindowRequest
import com.veyq.core.node.NodeEntity
import com.veyq.core.sharing.ShareGuestWrites
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import jakarta.transaction.Transactional

/**
 * The Memories half of a guest's write on a share: a share is rooted at a MEMORY (ADR 0029 §6, ADR 0030), so this is
 * the implementation for that kind. It only forwards to core's generic upload (its link paths and the completion dispatcher, which reaches
 * [MediaUploadCompletion]) and owns the delete-own.
 */
@ApplicationScoped
class MediaShareGuestWrites(
    private val uploads: BlobUploadHandler,
    private val completions: UploadCompletions,
    private val store: MediaStore,
) : ShareGuestWrites {

    // MEMORY only, not [MediaUploadCompletion]'s wider "may hold media" set (MEMORY or CHAPTER): a share's root is a Memory,
    // and what a CHAPTER may hold is not what may be shared.
    override fun supports(nodeKind: String): Boolean = nodeKind == NodeEntity.MEMORY

    override fun start(scopeId: String, shareId: String, identityId: String, declares: List<UploadDeclare>, domain: String?): List<UploadItemResult> =
        uploads.startForLink(scopeId, shareId, identityId, declares, domain)

    override fun window(scopeId: String, identityId: String, requests: List<WindowRequest>, domain: String?): List<WindowItemResult> =
        uploads.windowForScope(scopeId, UploadOpener.Contributor(identityId), requests, domain)

    override fun complete(scopeId: String, items: List<CompleteItem>, identityId: String, admitted: () -> Boolean): List<CompleteItemResult> =
        completions.complete(scopeId, items, UploadOpener.Contributor(identityId), admitted)

    /**
     * Authorized **iff** the media is in [scopeId] and its `share_identity_id` equals [identityId] — a single-row
     * **ownership** check, never gallery filtering. Deletes the media row + its derivative rows + all blob rows,
     * and puts their store objects (original + every derivative) on the reclaim list in the same transaction.
     */
    @Transactional
    override fun deleteOwn(scopeId: String, mediaId: String, identityId: String): Boolean {
        // The read is the row's lock (as `MediaStore.purgeMedia`): a second delete of the same upload, or a purge,
        // that holds it finishes first, and this then finds nothing — the 404 a replay gets. Read plainly, it deleted
        // rows already gone and answered 500.
        val media = MediaEntity.findById(mediaId, LockModeType.PESSIMISTIC_WRITE)
            ?.takeIf { it.scopeId == scopeId && it.shareIdentityId == identityId } ?: return false
        val nodeId = media.nodeId // captured before the row is deleted — the node whose gallery must reconcile
        store.purgeMediaRows(media) // derivative → media → blob rows, FK order (shared helper)
        // cr-0107 (ADR 0033): a media-row removal moves BOTH counters — media_revision (the change signal) and
        // delete_revision (which gates the id-set), so an open gallery's next poll gets the current mediaId set
        // and drops the removed tile. Same transaction ⇒ atomic with the delete. (A LIVE media is removed here —
        // unlike the two trash purges — so it bumps; purgeMediaRows leaves the revision bump to the caller.)
        store.bumpMediaRevision(nodeId, deleted = true)
        return true
    }
}
