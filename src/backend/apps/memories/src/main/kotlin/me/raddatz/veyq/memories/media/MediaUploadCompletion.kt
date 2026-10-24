package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.blob.BlobUploadHandler
import me.raddatz.veyq.core.blob.BlobUploadHandler.BlobVerify
import me.raddatz.veyq.core.blob.BlobUploadStore
import me.raddatz.veyq.core.blob.CompleteItem
import me.raddatz.veyq.core.blob.CompleteItemResult
import me.raddatz.veyq.core.blob.DerivativeDeclare
import me.raddatz.veyq.core.blob.SharedBlobSpec
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
 * `MediaDerivativeKind` values, and its payloads under [META] and [EXIF] — and the node's stated epoch under
 * [NODE_EPOCH], which [MediaStore.attach] judges in the attach's transaction (ADR 0109 point 6). Both paths build the item through [item],
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
    override fun complete(
        scopeId: String, node: NodeEntity, item: CompleteItem, opener: UploadOpener, admitted: () -> Boolean,
        attached: (objectId: String) -> Unit,
    ): CompleteItemResult {
        // The original is exactly one of an upload and a shared row (ADR 0013 § 5); a shared one is a member's alone - no
        // link role sends one (the share contract has no such field), and the seam holds it for every caller.
        val shared = item.originalShared
        if ((item.originalUploadId == null) == (shared == null)) return CompleteItemResult(null, null, "invalid_request")
        // Stated by every caller (the member's item, the guest's batch): an item without it is refused, never ungated.
        val nodeEpoch = item.payloads[NODE_EPOCH]?.toIntOrNull()?.takeIf { it >= 0 } ?: return CompleteItemResult(null, null, "invalid_request")
        if (shared != null && opener !is UploadOpener.Member) return CompleteItemResult(null, null, SHARED_SOURCE_INVALID)
        val original = item.originalUploadId?.let {
            blobs.findSession(scopeId, it, opener) ?: return CompleteItemResult(null, null, "upload_not_found")
        }
        val derivSessions = item.derivatives.map { d ->
            val s = blobs.findSession(scopeId, d.uploadId, opener)
                ?: return CompleteItemResult(null, null, "upload_not_found")
            d.kind to s
        }
        val allSessions = listOfNotNull(original) + derivSessions.map { it.second }
        // Each blob's K_blob wrap is sealed bound to (media, role) (ADR 0072): a session started for another media or
        // in another role would land a blob nobody can open where it is attached, so the item is refused whole. The
        // role is an open token (cr-files-application P3-S2), so a derivative also attaches only in a role Memories
        // names one in: a session started as ORIGINAL, or in any other token, and declared as a derivative of that same
        // kind would pass the binding check alone (the media contract's closed `MediaDerivativeKind` refuses such a
        // kind at the wire; this holds it for every caller of the seam).
        if ((original != null && !original.boundTo(item.ownerId, MemoryBlobRoles.ORIGINAL)) ||
            derivSessions.any { (kind, s) -> kind !in MemoryBlobRoles.DERIVATIVES || !s.boundTo(item.ownerId, kind) }
        ) {
            return CompleteItemResult(null, null, "upload_mismatch")
        }
        if (store.mediaIdTaken(item.ownerId)) {
            // Taken by a concurrent complete of this very upload that committed after the session read above? Then
            // the session is spent too, and a replay answers by the session first: `upload_not_found`.
            val spent = original != null && blobs.sessionSpent(original.id)
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

        val source = when {
            original != null -> OriginalAttach.Upload(original, manifests[original.id])
            else -> OriginalAttach.Shared(scopeId, shared!!)
        }
        val attach = {
            store.attach(
                item.ownerId, source, item.nodeId,
                derivSessions.map { (kind, s) -> DerivativeAttach(kind, s, manifests[s.id]) },
                item.payloads[META],
                item.payloads[EXIF],
                nodeEpoch,
                (opener as? UploadOpener.Contributor)?.shareIdentityId,
                (opener as? UploadOpener.Member)?.userId, // cr-0113: the member uploader → owner_key "user:{id}" (null on the contributor path)
                admitted,
                attached,
            )
        }
        // Run again, once, when a concurrent first upload committed the uploader's primary while this one waited for the
        // node: the run again locks it first, in the one lock order. This path holds no transaction of its own, so each
        // run is its own transaction and the first one has ended (AttachResult.PrimaryAppeared).
        return when (val r = attach().let { first -> if (first == AttachResult.PrimaryAppeared) attach() else first }) {
            is AttachResult.Attached -> CompleteItemResult(r.blobId, r.mediaId, null)
            AttachResult.NotAdmitted -> CompleteItemResult(null, null, "upload_not_found") // sessions spent concurrently, or no longer admitted
            AttachResult.SharedSourceInvalid -> CompleteItemResult(null, null, SHARED_SOURCE_INVALID)
            // ADR 0109 point 6: nothing stored, the item's uploads stay open for a retry under the node's current key.
            is AttachResult.EpochRefused -> CompleteItemResult(null, null, r.code)
            // A primary is never deleted apart from its node, so a run that found it locked it first: twice is a defect.
            AttachResult.PrimaryAppeared -> error("the uploader's primary appeared again after it was locked")
        }
    }

    companion object {
        /** The [CompleteItem.payloads] key of a medium's sealed meta (`wrappedMeta`). */
        const val META = "wrappedMeta"

        /** The [CompleteItem.payloads] key of a medium's sealed EXIF (`wrappedExif`). */
        const val EXIF = "wrappedExif"

        /**
         * The [CompleteItem.payloads] key of the epoch of the item's node as the client read it (ADR 0109 point 6),
         * decimal: core's item has no field for it and carries its payloads to this completion unread, so Memories'
         * two callers state it here and [complete] refuses an item without one. Not a sealed value — never stored.
         */
        const val NODE_EPOCH = "nodeEpoch"

        /** The item's error when `originalShared` names no blob the node holds, a blocked one, or a taken id. */
        const val SHARED_SOURCE_INVALID = "shared_source_invalid"

        /**
         * Core's generic item for one medium: [mediaId] as its `ownerId`, the two sealed values as its payloads (a
         * null one is left out — read back as null). The one mapping the member and the guest completion share.
         */
        fun item(
            mediaId: String, nodeId: String, originalUploadId: String?, derivatives: List<DerivativeDeclare>,
            wrappedMeta: String?, wrappedExif: String?, nodeEpoch: Int, originalShared: SharedBlobSpec? = null,
        ): CompleteItem = CompleteItem(
            ownerId = mediaId,
            nodeId = nodeId,
            originalUploadId = originalUploadId,
            derivatives = derivatives,
            payloads = listOfNotNull(wrappedMeta?.let { META to it }, wrappedExif?.let { EXIF to it }, NODE_EPOCH to nodeEpoch.toString()).toMap(),
            originalShared = originalShared,
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
