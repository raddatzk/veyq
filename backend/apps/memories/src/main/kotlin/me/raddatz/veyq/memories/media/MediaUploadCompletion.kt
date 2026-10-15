package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.blob.BlobUploadHandler
import me.raddatz.veyq.core.blob.BlobUploadHandler.BlobVerify
import me.raddatz.veyq.core.blob.BlobUploadStore
import me.raddatz.veyq.core.blob.CompleteItem
import me.raddatz.veyq.core.blob.CompleteItemResult
import me.raddatz.veyq.core.blob.DerivativeDeclare
import me.raddatz.veyq.core.blob.UploadCompletion
import me.raddatz.veyq.core.blob.UploadOpener
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import jakarta.enterprise.context.ApplicationScoped

/**
 * The Memories half of an upload's completion (en.memory.media, cr-0028): a verified original plus its role-tagged
 * derivatives become one medium of a MEMORY or CHAPTER — media, derivative and bucket rows, attributed to its
 * [UploadOpener]: a member's `owner_key` (cr-0113), or a **contributor `shareIdentityId`** (the READ_WRITE share
 * upload path, cr-0101). Core resolved the live target node and picked this implementation by its kind
 * (`UploadCompletions`); the member path and the guest path reach it alike.
 *
 * Core's item is generic: Memories reads its [CompleteItem.ownerId] as the media id, its derivative kinds as
 * `MediaDerivativeKind` values, and its payloads under [META] and [EXIF]. Both paths build the item through [item],
 * and map [CompleteItemResult.ownerId] back to their response's `mediaId`.
 */
@ApplicationScoped
class MediaUploadCompletion(
    private val blobs: BlobUploadStore,
    private val uploads: BlobUploadHandler,
    private val store: MediaStore,
) : UploadCompletion {

    override fun accepts(nodeKind: String): Boolean = attachesMedia(nodeKind)

    /**
     * Complete one logical media (original + derivative uploads). The item is **atomic**: the node + every
     * upload session are resolved first, then **every** blob is declare-then-verified, and only if all pass are
     * the blobs + media + derivative rows attached. On a budget failure the item's staged objects are purged.
     */
    override fun complete(scopeId: String, node: NodeEntity, item: CompleteItem, opener: UploadOpener, admitted: () -> Boolean): CompleteItemResult {
        val original = blobs.findSession(scopeId, item.originalUploadId, opener)
            ?: return CompleteItemResult(null, null, "upload_not_found")
        val derivSessions = item.derivatives.map { d ->
            val s = blobs.findSession(scopeId, d.uploadId, opener)
                ?: return CompleteItemResult(null, null, "upload_not_found")
            d.kind to s
        }
        val allSessions = listOf(original) + derivSessions.map { it.second }
        // Each blob's K_blob wrap is sealed bound to (media, role) (ADR 0072): a session started for another media or
        // in another role would land a blob nobody can open where it is attached, so the item is refused whole. The
        // role is an open token (cr-files-application P3-S2), so a derivative also attaches only in a role Memories
        // names one in: a session started as ORIGINAL, or in any other token, and declared as a derivative of that same
        // kind would pass the binding check alone (the media contract's closed `MediaDerivativeKind` refuses such a
        // kind at the wire; this holds it for every caller of the seam).
        if (!original.boundTo(item.ownerId, MemoryBlobRoles.ORIGINAL) ||
            derivSessions.any { (kind, s) -> kind !in MemoryBlobRoles.DERIVATIVES || !s.boundTo(item.ownerId, kind) }
        ) {
            return CompleteItemResult(null, null, "upload_mismatch")
        }
        if (store.mediaIdTaken(item.ownerId)) {
            // Taken by a concurrent complete of this very upload that committed after the session read above? Then
            // the session is spent too, and a replay answers by the session first: `upload_not_found`.
            val spent = blobs.sessionSpent(original.id)
            return CompleteItemResult(null, null, if (spent) "upload_not_found" else "id_taken")
        }

        // declare-then-verify every blob up front; a single budget failure fails (and purges) the whole item
        val manifests = HashMap<String, List<Long>?>()
        for (session in allSessions) {
            when (val v = uploads.verifyBlob(session)) {
                is BlobVerify.Ok -> manifests[session.id] = v.manifest
                BlobVerify.Budget -> {
                    uploads.purge(allSessions)
                    return CompleteItemResult(null, null, "budget_exceeded")
                }
            }
        }

        val (blobId, mediaId) = store.attach(
            item.ownerId, original, item.nodeId, manifests[original.id],
            derivSessions.map { (kind, s) -> DerivativeAttach(kind, s, manifests[s.id]) },
            item.payloads[META],
            item.payloads[EXIF],
            (opener as? UploadOpener.Contributor)?.shareIdentityId,
            (opener as? UploadOpener.Member)?.userId, // cr-0113: the member uploader → owner_key "user:{id}" (null on the contributor path)
            admitted,
        ) ?: return CompleteItemResult(null, null, "upload_not_found") // sessions spent concurrently, or no longer admitted
        return CompleteItemResult(blobId, mediaId, null)
    }

    companion object {
        /** The [CompleteItem.payloads] key of a medium's sealed meta (`wrappedMeta`). */
        const val META = "wrappedMeta"

        /** The [CompleteItem.payloads] key of a medium's sealed EXIF (`wrappedExif`). */
        const val EXIF = "wrappedExif"

        /**
         * Core's generic item for one medium: [mediaId] as its `ownerId`, the two sealed values as its payloads (a
         * null one is left out — read back as null). The one mapping the member and the guest completion share.
         */
        fun item(
            mediaId: String, nodeId: String, originalUploadId: String, derivatives: List<DerivativeDeclare>,
            wrappedMeta: String?, wrappedExif: String?,
        ): CompleteItem = CompleteItem(
            ownerId = mediaId,
            nodeId = nodeId,
            originalUploadId = originalUploadId,
            derivatives = derivatives,
            payloads = listOfNotNull(wrappedMeta?.let { META to it }, wrappedExif?.let { EXIF to it }).toMap(),
        )

        /**
         * Allow-list, not a FOLDER deny-list: media attaches only to a node that owns a `K_node` and a gallery.
         * Behaviour-neutral while `NodesHandler.parentAllows` ends `else -> false` (the kinds are closed: FOLDER,
         * MEMORY, CHAPTER). It stops being neutral the day a kind is added — which is the point: a new kind then
         * refuses media until someone opts it in here, instead of silently inheriting it. (Core's dispatcher refuses
         * a kind no application accepts, so the default-deny holds in core too.) The cover path asks it as well.
         */
        fun attachesMedia(kind: String): Boolean = kind == MemoriesNodeKinds.MEMORY || kind == MemoriesNodeKinds.CHAPTER
    }
}
