package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.blob.BlobUploadHandler
import me.raddatz.veyq.core.blob.UploadDeclare
import me.raddatz.veyq.core.blob.UploadItemResult
import me.raddatz.veyq.core.blob.UploadOpener
import me.raddatz.veyq.core.blob.WindowItemResult
import me.raddatz.veyq.core.blob.WindowRequest
import me.raddatz.veyq.core.content.NodesStore
import me.raddatz.veyq.core.sharing.ShareGuestWrites
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import jakarta.transaction.Transactional

/**
 * The Memories half of a guest's write on a share: a guest uploads into a MEMORY or a CHAPTER — the root of a Memories
 * share (ADR 0029 §6, ADR 0030, ADR 0080 decision 13), or one below the root of any share, a core `FOLDER`'s included
 * (ADR 0080 decision 6) — so this is the implementation for those kinds. Its seam half only forwards
 * core's guest transport (start, window) to core's generic upload. [deleteOwn] is Memories' own: [ShareMediaHandler] calls it past core's guest gate, and completes a
 * guest's uploads itself through core's completion dispatcher (which reaches [MediaUploadCompletion]).
 */
@ApplicationScoped
class MediaShareGuestWrites(
    private val uploads: BlobUploadHandler,
    private val store: MediaStore,
    private val tree: NodesStore,
) : ShareGuestWrites {

    // The kinds a guest uploads into — the root of a Memories share, or a Memory or chapter below a share's root (ADR
    // 0080 decision 6: the role applies below the root as Memories allows it). [MemoriesNodeKinds.SHARE_ROOTS], not
    // [MediaUploadCompletion]'s "may hold media" set: the two hold the same kinds today, but what may hold media is not
    // by that what a guest may write into.
    override fun supports(nodeKind: String): Boolean = nodeKind in MemoriesNodeKinds.SHARE_ROOTS
    override fun start(
        scopeId: String, shareId: String, targetKind: String, identityId: String, declares: List<UploadDeclare>, domain: String?,
    ): List<UploadItemResult> =
        uploads.startForLink(scopeId, shareId, targetKind, identityId, declares, domain)

    override fun window(scopeId: String, identityId: String, requests: List<WindowRequest>, domain: String?): List<WindowItemResult> =
        uploads.windowForScope(scopeId, UploadOpener.Contributor(identityId), requests, domain)

    /**
     * Authorized **iff** the media is in [scopeId], its `share_identity_id` equals [identityId] — a single-row
     * **ownership** check, never gallery filtering — and it lies on a node of a kind a guest writes into, inside the
     * share's subtree under [rootNodeId] (ADR 0080 decision 6: a folder link's guest deletes its own in any Memory or
     * chapter below the root; one a member has since moved out of the subtree is no longer the link's to touch). The
     * walk is structural ([NodesStore.withinSubtree] ignores the trash), so a medium under a trashed root is still
     * deleted as before. Deletes the media row + its derivative rows + all blob rows, and puts their store objects
     * (original + every derivative) on the reclaim list in the same transaction.
     *
     * The subtree is read under the media row's lock only, not the scope's: a member's move racing the delete of the
     * guest's own upload is decided either way without harm (the medium is the guest's either way), and taking the
     * scope row here would put a new lock order onto every guest delete.
     */
    @Transactional
    fun deleteOwn(scopeId: String, rootNodeId: String, mediaId: String, identityId: String): Boolean {
        // The read is the row's lock (as `MediaStore.purgeMedia`): a second delete of the same upload, or a purge,
        // that holds it finishes first, and this then finds nothing — the 404 a replay gets. Read plainly, it deleted
        // rows already gone and answered 500.
        val media = MediaEntity.findById(mediaId, LockModeType.PESSIMISTIC_WRITE)
            ?.takeIf { it.scopeId == scopeId && it.shareIdentityId == identityId } ?: return false
        val kind = tree.findDeletableInScope(scopeId, media.nodeId)?.kind ?: return false
        if (!supports(kind) || !tree.withinSubtree(scopeId, rootNodeId, media.nodeId)) return false
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
