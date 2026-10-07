package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.blob.BlobUploadHandler
import me.raddatz.veyq.core.blob.UploadDeclare
import me.raddatz.veyq.core.blob.UploadItemResult
import me.raddatz.veyq.core.blob.UploadOpener
import me.raddatz.veyq.core.blob.WindowItemResult
import me.raddatz.veyq.core.blob.WindowRequest
import me.raddatz.veyq.core.sharing.ShareGuestWrites
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import jakarta.transaction.Transactional

/**
 * The Memories half of a guest's write on a share: a share of Memories is rooted at a MEMORY or a CHAPTER (ADR 0029
 * §6, ADR 0030, ADR 0080 decision 13), so this is the implementation for those kinds. Its seam half only forwards
 * core's guest transport (start, window) to core's generic upload. [deleteOwn] is Memories' own: [ShareMediaHandler] calls it past core's guest gate, and completes a
 * guest's uploads itself through core's completion dispatcher (which reaches [MediaUploadCompletion]).
 */
@ApplicationScoped
class MediaShareGuestWrites(
    private val uploads: BlobUploadHandler,
    private val store: MediaStore,
) : ShareGuestWrites {

    // The kinds Memories roots a share at ([MemoriesNodeKinds.SHARE_ROOTS]), not [MediaUploadCompletion]'s "may hold
    // media" set: the two hold the same kinds today, but what may hold media is not by that what may be shared.
    override fun supports(nodeKind: String): Boolean = nodeKind in MemoriesNodeKinds.SHARE_ROOTS

    override fun start(scopeId: String, shareId: String, identityId: String, declares: List<UploadDeclare>, domain: String?): List<UploadItemResult> =
        uploads.startForLink(scopeId, shareId, identityId, declares, domain)

    override fun window(scopeId: String, identityId: String, requests: List<WindowRequest>, domain: String?): List<WindowItemResult> =
        uploads.windowForScope(scopeId, UploadOpener.Contributor(identityId), requests, domain)

    /**
     * Authorized **iff** the media is in [scopeId] and its `share_identity_id` equals [identityId] — a single-row
     * **ownership** check, never gallery filtering. Deletes the media row + its derivative rows + all blob rows,
     * and puts their store objects (original + every derivative) on the reclaim list in the same transaction.
     */
    @Transactional
    fun deleteOwn(scopeId: String, mediaId: String, identityId: String): Boolean {
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
