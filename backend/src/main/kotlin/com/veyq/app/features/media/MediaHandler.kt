package com.veyq.app.features.media

import com.veyq.app.features.content.NodeEntity
import com.veyq.app.shared.blobstore.BlobStore
import com.veyq.app.shared.blobstore.BlobStore.Companion.chunkKey
import com.veyq.app.shared.config.VeyqConfig
import com.veyq.app.shared.http.FailureStatus.BAD_REQUEST
import com.veyq.app.shared.http.FailureStatus.FORBIDDEN
import com.veyq.app.shared.http.FailureStatus.NOT_FOUND
import com.veyq.app.shared.http.FailureStatus.UNPROCESSABLE
import com.veyq.app.shared.http.Failures
import com.veyq.app.shared.http.HttpFailure
import com.veyq.app.shared.http.httpFailure
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/** A presigned URL for one chunk (index in chunk order). */
data class PresignedRef(val index: Int, val url: String)

/** One blob to start: declared shape + the K_blob wrap. */
data class UploadDeclare(val chunkCount: Int, val byteSize: Long, val wrappedBlobKey: String)

/** One derivative upload of a media to complete: its role + the upload session id. */
data class DerivativeDeclare(val kind: String, val uploadId: String)

/** One logical media to complete: the original upload + role-tagged derivative uploads, attached to a node. */
data class CompleteItem(
    val nodeId: String,
    val originalUploadId: String,
    val derivatives: List<DerivativeDeclare>,
    val wrappedMeta: String?,
    val wrappedExif: String?,
    val bucketId: String? = null, // cr-0113: an explicit target bucket; null = the uploader's primary
)

/** Per-item result of `start`: success carries the session + presigned PUTs (null when the store can't presign). */
data class UploadItemResult(val uploadId: String?, val presignedPuts: List<PresignedRef>?, val error: String?)

/** Per-item result of `complete`. */
data class CompleteItemResult(val blobId: String?, val mediaId: String?, val error: String?)

/** Per-item result of `presign-download`. */
data class DownloadItemResult(val presignedGets: List<PresignedRef>?, val error: String?)

sealed interface StartOutcome {
    data class Results(val items: List<UploadItemResult>) : StartOutcome
    object NoScope : StartOutcome, HttpFailure by Failures.scopeNotFound
}

sealed interface CompleteOutcome {
    data class Results(val items: List<CompleteItemResult>) : CompleteOutcome
    object NoScope : CompleteOutcome, HttpFailure by Failures.scopeNotFound
}

sealed interface ListMediaOutcome {
    /** A conditional-GET page (cr-0107, ADR 0033): the keyset delta + both revisions + the id-set — the latter present **only when a delete advanced `delete_revision`** past the client's `sinceDelete`. */
    data class Page(
        val rows: List<MediaRow>,
        val mediaRevision: Int,
        val deleteRevision: Int,
        val mediaIds: List<String>?,
    ) : ListMediaOutcome
    /** The client's `If-None-Match` equals the node's `media_revision` — nothing changed (a 304, no media query ran). */
    object NotModified : ListMediaOutcome
    object NoNode : ListMediaOutcome, HttpFailure by Failures.nodeNotFound
}

sealed interface DownloadOutcome {
    data class Results(val items: List<DownloadItemResult>) : DownloadOutcome
    object NoScope : DownloadOutcome, HttpFailure by Failures.scopeNotFound
}

sealed interface MediaExifOutcome {
    /** The media's sealed heavy EXIF (opaque base64), or null when none was sealed. */
    data class Found(val wrappedExif: String?) : MediaExifOutcome
    object NoMedia : MediaExifOutcome, HttpFailure by httpFailure("media_not_found", NOT_FOUND)
}

sealed interface MetaOutcome {
    /** The media's sealed listed-metadata blob (opaque base64), or null when none/cleared. */
    data class Found(val wrappedMeta: String?) : MetaOutcome
    object NoMedia : MetaOutcome, HttpFailure by httpFailure("media_not_found", NOT_FOUND)
}

sealed interface CoverCommitOutcome {
    /** The committed cover blob ref (any prior cover blob was reclaimed). */
    data class Ok(val ref: BlobRefData) : CoverCommitOutcome
    /** No such scope/node/upload, or the caller holds no grant (a 404, no reveal). */
    object NoTarget : CoverCommitOutcome, HttpFailure by Failures.nodeNotFound
    /** The target node is a FOLDER (owns no `K_mem`) — a 422. */
    object IllegalTarget : CoverCommitOutcome, HttpFailure by httpFailure("illegal_target", UNPROCESSABLE)
    /** The upload failed declare-then-verify (staged objects purged) — a 400. */
    object Budget : CoverCommitOutcome, HttpFailure by httpFailure("budget_exceeded", BAD_REQUEST)
}

sealed interface CoverDeleteOutcome {
    /** The cover blob was detached + reclaimed (or the node had none — idempotent). */
    object Done : CoverDeleteOutcome
    /** No such scope/node, or the caller holds no grant (a 404). */
    object NoTarget : CoverDeleteOutcome, HttpFailure by Failures.nodeNotFound
}

sealed interface DeleteMediaOutcome {
    /** The media is trashed (or was already trashed — idempotent). */
    object Done : DeleteMediaOutcome
    /** No such scope/media, or the caller holds no grant (a 404, no reveal). */
    object NoMedia : DeleteMediaOutcome, HttpFailure by httpFailure("media_not_found", NOT_FOUND)
}

sealed interface RestoreMediaOutcome {
    /** The media is restored (or was already live — idempotent). */
    object Done : RestoreMediaOutcome
    /** No such scope/media (e.g. already purged), or the caller holds no grant (a 404). */
    object NoMedia : RestoreMediaOutcome, HttpFailure by httpFailure("media_not_found", NOT_FOUND)
}

sealed interface PurgeMediaOutcome {
    /** The trashed media was permanently deleted. */
    object Done : PurgeMediaOutcome
    /** No such trashed media (unknown / already purged / still live), or no grant (a 404). */
    object NoMedia : PurgeMediaOutcome, HttpFailure by httpFailure("media_not_found", NOT_FOUND)
}

sealed interface ListBucketsOutcome {
    data class Ok(val buckets: List<BucketRow>) : ListBucketsOutcome
    /** No such scope/node, or the caller holds no grant (a 404, no reveal). */
    object NoNode : ListBucketsOutcome, HttpFailure by Failures.nodeNotFound
}

sealed interface CreateBucketOutcome {
    data class Ok(val bucket: BucketRow) : CreateBucketOutcome
    /** A given parentBucketId is missing or not a primary (sub-buckets nest one level) — a 403. */
    object Forbidden : CreateBucketOutcome, HttpFailure by httpFailure("illegal_target", FORBIDDEN)
    /** No such scope/node, or the caller holds no grant (a 404). */
    object NoNode : CreateBucketOutcome, HttpFailure by Failures.nodeNotFound
}

sealed interface RenameBucketOutcome {
    data class Ok(val bucket: BucketRow) : RenameBucketOutcome
    /** No such scope/node/bucket, or the caller holds no grant (a 404). */
    object NoBucket : RenameBucketOutcome, HttpFailure by httpFailure("bucket_not_found", NOT_FOUND)
    /** The bucket is a primary (not renameable) — a 403. */
    object Forbidden : RenameBucketOutcome, HttpFailure by httpFailure("illegal_target", FORBIDDEN)
}

sealed interface DeleteBucketOutcome {
    /** The sub-bucket was deleted + its media reparented (or it did not exist — idempotent). */
    object Done : DeleteBucketOutcome
    /** No such scope/node, or the caller holds no grant (a 404). */
    object NoNode : DeleteBucketOutcome, HttpFailure by Failures.nodeNotFound
    /** The bucket is a primary (not deletable) — a 403. */
    object Forbidden : DeleteBucketOutcome, HttpFailure by httpFailure("illegal_target", FORBIDDEN)
}

sealed interface MoveMediaOutcome {
    /** The media were moved. */
    object Done : MoveMediaOutcome
    /** No such scope/node, or the caller holds no grant (a 404). */
    object NoNode : MoveMediaOutcome, HttpFailure by Failures.nodeNotFound
    /** A medium or the target bucket is not the caller's own — a 403. */
    object Forbidden : MoveMediaOutcome, HttpFailure by httpFailure("illegal_target", FORBIDDEN)
}

/**
 * Use-case logic for media blobs (en.memory.media, cr-0028). Operator-blind, batch-shaped, per-item results.
 * Scope access = the caller holds a membership (a non-member sees a top-level 404, not a reveal). Direct-to-
 * store: bytes never flow through here — `start` presigns PUTs, `complete` stats the stored objects
 * (declare-then-verify), `download` presigns GETs.
 *
 * Two budget gates (ADR 0013 §3): the **declared** shape is capped at `start` (`budget_exceeded` per item),
 * and the **actual** object sizes are verified against the declaration at `complete` (purge + `budget_exceeded`
 * on excess). The in-memory dev store has no transport, so its `complete` trusts the declaration (manifest
 * null); the byte path is covered by the opt-in store e2e.
 */
@ApplicationScoped
class MediaHandler(
    private val store: MediaStore,
    private val blobStore: BlobStore,
    config: VeyqConfig,
) {
    private val maxBytes: Long = config.blob().maxBytes()
    private val maxChunks: Int = config.blob().maxChunks()

    fun start(userId: String, scopeId: String, declares: List<UploadDeclare>): StartOutcome {
        if (!store.isMember(userId, scopeId)) return StartOutcome.NoScope
        return StartOutcome.Results(startForScope(scopeId, declares))
    }

    /**
     * The member-less core of [start]: reserve a session + presign PUTs per declared blob, scoped only by
     * `scopeId`. The caller does its own authorization — the member gate ([start]) or the share + contributor
     * token gate (the CONTRIBUTOR share upload path, cr-0101). Bytes never flow through here.
     */
    fun startForScope(scopeId: String, declares: List<UploadDeclare>): List<UploadItemResult> =
        declares.map { startOne(scopeId, it) }

    private fun startOne(scopeId: String, d: UploadDeclare): UploadItemResult {
        if (d.chunkCount < 1 || d.chunkCount > maxChunks || d.byteSize < 0 || d.byteSize > maxBytes) {
            return UploadItemResult(uploadId = null, presignedPuts = null, error = "budget_exceeded")
        }
        val storageKey = UUID.randomUUID().toString()
        val uploadId = store.createSession(scopeId, storageKey, d.chunkCount, d.byteSize, d.wrappedBlobKey)
        // The session is metadata; presigned PUTs require a transport-capable store. Only the %test in-memory stub
        // cannot presign (puts = null) — %dev/%prod always have a real store (BlobStoreStartupCheck, ADR 0013).
        val puts = if (blobStore.canPresign) {
            (0 until d.chunkCount).map { i -> PresignedRef(i, blobStore.presignPut(chunkKey(storageKey, i))!!) }
        } else {
            null
        }
        return UploadItemResult(uploadId = uploadId, presignedPuts = puts, error = null)
    }

    fun complete(userId: String, scopeId: String, items: List<CompleteItem>): CompleteOutcome {
        if (!store.isMember(userId, scopeId)) return CompleteOutcome.NoScope
        return CompleteOutcome.Results(completeForNode(scopeId, items, null, userId)) // cr-0113: member uploader → owner_key
    }

    /**
     * The member-less core of [complete]: verify + attach each item, scoped only by `scopeId`, optionally
     * stamping a **contributor `shareIdentityId`** (the CONTRIBUTOR share upload path, cr-0101). The caller does
     * its own authorization. Each item's `nodeId` is honoured (the share path sets it to the shared root node).
     */
    fun completeForNode(scopeId: String, items: List<CompleteItem>, shareIdentityId: String?, uploaderUserId: String? = null): List<CompleteItemResult> =
        items.map { completeOne(scopeId, it, shareIdentityId, uploaderUserId) }

    /**
     * Complete one logical media (original + derivative uploads). The item is **atomic**: the node + every
     * upload session are resolved first, then **every** blob is declare-then-verified, and only if all pass are
     * the blobs + media + derivative rows attached. On a budget failure the item's staged objects are purged.
     */
    private fun completeOne(scopeId: String, item: CompleteItem, shareIdentityId: String? = null, uploaderUserId: String? = null): CompleteItemResult {
        val node = store.findNode(scopeId, item.nodeId)
            ?: return CompleteItemResult(null, null, "node_not_found")
        if (node.kind == NodeEntity.FOLDER) return CompleteItemResult(null, null, "illegal_target")

        val original = store.findSession(scopeId, item.originalUploadId)
            ?: return CompleteItemResult(null, null, "upload_not_found")
        val derivSessions = item.derivatives.map { d ->
            val s = store.findSession(scopeId, d.uploadId)
                ?: return CompleteItemResult(null, null, "upload_not_found")
            d.kind to s
        }
        val allSessions = listOf(original) + derivSessions.map { it.second }

        // declare-then-verify every blob up front; a single budget failure fails (and purges) the whole item
        val manifests = HashMap<String, List<Long>?>()
        for (session in allSessions) {
            when (val v = verifyBlob(session)) {
                is BlobVerify.Ok -> manifests[session.id] = v.manifest
                BlobVerify.Budget -> {
                    purge(allSessions)
                    return CompleteItemResult(null, null, "budget_exceeded")
                }
            }
        }

        val (blobId, mediaId) = store.attach(
            original, item.nodeId, manifests[original.id],
            derivSessions.map { (kind, s) -> DerivativeAttach(kind, s, manifests[s.id]) },
            item.wrappedMeta,
            item.wrappedExif,
            shareIdentityId,
            uploaderUserId, // cr-0113: the member uploader → owner_key "user:{id}" (null on the contributor path)
            item.bucketId,  // cr-0113: an explicit target bucket, else the uploader's primary
        )
        return CompleteItemResult(blobId, mediaId, null)
    }

    private sealed interface BlobVerify {
        data class Ok(val manifest: List<Long>?) : BlobVerify
        object Budget : BlobVerify
    }

    /** Stat a session's stored objects against its declaration. Ok carries the manifest (null on the dev store). */
    private fun verifyBlob(session: UploadSessionEntity): BlobVerify {
        if (!blobStore.canPresign) return BlobVerify.Ok(null) // dev store: no transport, trust the declaration
        val keys = (0 until session.chunkCount).map { chunkKey(session.storageKey, it) }
        val sizes = keys.map { blobStore.stat(it) }
        if (sizes.any { it == null } || sizes.filterNotNull().sum() > session.byteSize) return BlobVerify.Budget
        return BlobVerify.Ok(sizes.filterNotNull())
    }

    /**
     * Best-effort purge of every chunk object staged for the item's sessions (after a budget failure). Objects
     * only — the sessions' **rows** are deliberately left standing here rather than deleted inline, and
     * [UploadSessionPurge] reclaims them on the same age predicate as a genuinely abandoned upload.
     */
    private fun purge(sessions: List<UploadSessionEntity>) {
        if (!blobStore.canPresign) return
        blobStore.delete(sessions.flatMap { s -> (0 until s.chunkCount).map { chunkKey(s.storageKey, it) } })
    }

    /**
     * One `listNodeMedia` poll (cr-0107, ADR 0033). Reads the node's `media_revision` + `delete_revision` as a
     * **consistent pair** (one row). When the client's [ifNoneMatch] still equals `media_revision` ⇒ **NotModified**
     * (a 304 — no media query, the idle fast path). Otherwise a **keyset delta** past the [since] cursor, plus both
     * revisions and — **only when [sinceDelete] lags `delete_revision`** (a real delete since) — the current
     * `mediaId` set for delete-reconcile. Non-member / unknown node ⇒ NoNode (a 404, no reveal).
     */
    fun listMedia(
        userId: String,
        scopeId: String,
        nodeId: String,
        ifNoneMatch: Int?,
        since: MediaCursor?,
        sinceDelete: Int,
        trashed: Boolean,
    ): ListMediaOutcome {
        if (!store.isMember(userId, scopeId)) return ListMediaOutcome.NoNode
        val node = store.findNode(scopeId, nodeId) ?: return ListMediaOutcome.NoNode
        // The Trash tab (cr-0110): a plain full list of the node's trashed media (carrying deletedAt/deletedBy), no
        // conditional-GET/delta — the trash isn't live-polled, so the ETag/since/id-set machinery doesn't apply.
        if (trashed) return ListMediaOutcome.Page(store.listNodeTrashed(scopeId, nodeId), node.mediaRevision, node.deleteRevision, null)
        val mediaRevision = node.mediaRevision
        val deleteRevision = node.deleteRevision
        if (ifNoneMatch != null && ifNoneMatch == mediaRevision) return ListMediaOutcome.NotModified
        val rows = store.listNodeMediaSince(scopeId, nodeId, since)
        val mediaIds = if (sinceDelete < deleteRevision) store.listNodeMediaIds(scopeId, nodeId) else null
        return ListMediaOutcome.Page(rows, mediaRevision, deleteRevision, mediaIds)
    }

    /** The media's lazy heavy EXIF tier for the lightbox. Non-member or unknown media → NoMedia (a 404, no reveal). */
    fun mediaExif(userId: String, scopeId: String, mediaId: String): MediaExifOutcome {
        if (!store.isMember(userId, scopeId)) return MediaExifOutcome.NoMedia
        val media = store.findMedia(scopeId, mediaId) ?: return MediaExifOutcome.NoMedia
        return MediaExifOutcome.Found(media.wrappedExif)
    }

    /** Overwrite the media's sealed listed-metadata blob (opaque re-seal, e.g. a corrected date). */
    fun updateMeta(userId: String, scopeId: String, mediaId: String, wrappedMeta: String?): MetaOutcome {
        if (!store.isMember(userId, scopeId)) return MetaOutcome.NoMedia
        val media = store.setMeta(scopeId, mediaId, wrappedMeta) ?: return MetaOutcome.NoMedia
        return MetaOutcome.Found(media.wrappedMeta)
    }

    /**
     * Trash a media (uc.memory.delete-media, cr-0110): **any member** of the scope may soft-delete it into the
     * per-Memory trash (reversible; the irreversible purge is MediaTrashPurge's). Non-member or unknown media →
     * NoMedia (a 404, no reveal). Idempotent.
     */
    fun deleteMedia(userId: String, scopeId: String, mediaId: String): DeleteMediaOutcome {
        if (!store.isMember(userId, scopeId)) return DeleteMediaOutcome.NoMedia
        return if (store.trashMedia(scopeId, mediaId, userId) != null) DeleteMediaOutcome.Done else DeleteMediaOutcome.NoMedia
    }

    /** Restore a trashed media (cr-0110): any member may bring it back before the purge. 404 on unknown/purged. */
    fun restoreMedia(userId: String, scopeId: String, mediaId: String): RestoreMediaOutcome {
        if (!store.isMember(userId, scopeId)) return RestoreMediaOutcome.NoMedia
        return if (store.restoreMedia(scopeId, mediaId) != null) RestoreMediaOutcome.Done else RestoreMediaOutcome.NoMedia
    }

    /**
     * Permanently delete a trashed media NOW (uc.memory.delete-media, cr-0111): any member may expedite the purge
     * of an already-trashed item (the security-model reversible/irreversible split). Non-member, unknown, or a
     * still-LIVE media → NoMedia (a 404). The blob-object purge is best-effort.
     */
    fun purgeMedia(userId: String, scopeId: String, mediaId: String): PurgeMediaOutcome {
        if (!store.isMember(userId, scopeId)) return PurgeMediaOutcome.NoMedia
        val reclaimKeys = store.purgeMedia(scopeId, mediaId) ?: return PurgeMediaOutcome.NoMedia
        blobStore.delete(reclaimKeys) // best-effort store purge
        return PurgeMediaOutcome.Done
    }

    /**
     * Commit an uploaded blob as the node's **standalone cover** (cr-0073): resolve the node + session, run the
     * same declare-then-verify as `complete` (stat the stored objects against the byte budget, purge + fail on
     * excess), then persist one `veyq_blob` + point the node's `cover_blob_id` at it, atomically reclaiming any
     * prior cover blob (its store objects are purged here). No `veyq_media` row — the cover never lists in the
     * gallery. Guards mirror `complete`: member gate (non-member → NoTarget/404), FOLDER → IllegalTarget/422.
     */
    fun commitCover(userId: String, scopeId: String, nodeId: String, uploadId: String): CoverCommitOutcome {
        if (!store.isMember(userId, scopeId)) return CoverCommitOutcome.NoTarget
        val node = store.findNode(scopeId, nodeId) ?: return CoverCommitOutcome.NoTarget
        if (node.kind == NodeEntity.FOLDER) return CoverCommitOutcome.IllegalTarget
        val session = store.findSession(scopeId, uploadId) ?: return CoverCommitOutcome.NoTarget
        val manifest = when (val v = verifyBlob(session)) {
            is BlobVerify.Ok -> v.manifest
            BlobVerify.Budget -> {
                purge(listOf(session))
                return CoverCommitOutcome.Budget
            }
        }
        val rows = store.commitCover(node.id, session, manifest)
        blobStore.delete(rows.reclaimKeys) // best-effort purge of the superseded prior cover blob's objects
        return CoverCommitOutcome.Ok(rows.ref)
    }

    /** Detach + reclaim the node's standalone cover blob (cr-0073). Idempotent — a node with no cover → Done. */
    fun deleteCover(userId: String, scopeId: String, nodeId: String): CoverDeleteOutcome {
        if (!store.isMember(userId, scopeId)) return CoverDeleteOutcome.NoTarget
        if (store.findNode(scopeId, nodeId) == null) return CoverDeleteOutcome.NoTarget
        val reclaimKeys = store.clearCover(scopeId, nodeId)
        blobStore.delete(reclaimKeys) // best-effort store purge
        return CoverDeleteOutcome.Done
    }

    fun download(userId: String, scopeId: String, blobIds: List<String>): DownloadOutcome {
        if (!store.isMember(userId, scopeId)) return DownloadOutcome.NoScope
        return DownloadOutcome.Results(blobIds.map { downloadOne(scopeId, it) })
    }

    private fun downloadOne(scopeId: String, blobId: String): DownloadItemResult {
        val blob = store.findBlob(scopeId, blobId)
            ?: return DownloadItemResult(presignedGets = null, error = "upload_not_found")
        if (!blobStore.canPresign) return DownloadItemResult(presignedGets = null, error = "store_unavailable")
        val gets = (0 until blob.chunkCount).map { i -> PresignedRef(i, blobStore.presignGet(chunkKey(blob.storageKey, i))!!) }
        return DownloadItemResult(presignedGets = gets, error = null)
    }

    // --- buckets (uc.memory.buckets, ADR 0034, cr-0113 · curation relaxed cr-0123) — member-gated: a member may
    // curate any owner-root's SUB-buckets (create/rename/delete) + move media WITHIN one root, but never a primary
    // and never across owners (attribution — the owner_key — is preserved on every path) ---

    /** List the node's uploader buckets for the "Beiträge" tab. Non-member / unknown node → NoNode (404, no reveal). */
    fun listBuckets(userId: String, scopeId: String, nodeId: String): ListBucketsOutcome {
        if (!store.isMember(userId, scopeId)) return ListBucketsOutcome.NoNode
        if (store.findNode(scopeId, nodeId) == null) return ListBucketsOutcome.NoNode
        return ListBucketsOutcome.Ok(store.listBuckets(scopeId, nodeId))
    }

    /**
     * Create a sub-bucket under a primary root. `parentBucketId == null` ⇒ the caller's own primary; else it must
     * be a **primary** (any owner's — so a member can add sub-buckets under a contributor's root), and the new sub
     * inherits that root's owner_key. A missing/non-primary parent → Forbidden (403). Non-member / unknown node → NoNode (404).
     */
    fun createBucket(userId: String, scopeId: String, nodeId: String, sealedName: String, parentBucketId: String?): CreateBucketOutcome {
        if (!store.isMember(userId, scopeId)) return CreateBucketOutcome.NoNode
        if (store.findNode(scopeId, nodeId) == null) return CreateBucketOutcome.NoNode
        val ownerKey = if (parentBucketId != null) {
            val parent = store.findBucket(scopeId, nodeId, parentBucketId) ?: return CreateBucketOutcome.Forbidden
            if (!parent.isPrimary) return CreateBucketOutcome.Forbidden // nest one level, under a primary only
            parent.ownerKey
        } else {
            OwnerKey.User(userId).format()
        }
        return CreateBucketOutcome.Ok(store.createSubBucket(scopeId, nodeId, ownerKey, sealedName))
    }

    /** Rename a sub-bucket (any member may — incl. a contributor's, cr-0123). A primary is not renameable (Forbidden → 403). */
    fun renameBucket(userId: String, scopeId: String, nodeId: String, bucketId: String, sealedName: String): RenameBucketOutcome {
        if (!store.isMember(userId, scopeId)) return RenameBucketOutcome.NoBucket
        val b = store.findBucket(scopeId, nodeId, bucketId) ?: return RenameBucketOutcome.NoBucket
        if (b.isPrimary) return RenameBucketOutcome.Forbidden
        val row = store.renameBucket(bucketId, sealedName) ?: return RenameBucketOutcome.NoBucket
        return RenameBucketOutcome.Ok(row)
    }

    /**
     * Delete a sub-bucket — its media reparent to that root's owner primary (never deleted). Any member may
     * (incl. a contributor's sub, cr-0123); a primary is not deletable (Forbidden → 403). Unknown bucket is
     * idempotent (Done → 204).
     */
    fun deleteBucket(userId: String, scopeId: String, nodeId: String, bucketId: String): DeleteBucketOutcome {
        if (!store.isMember(userId, scopeId)) return DeleteBucketOutcome.NoNode
        val b = store.findBucket(scopeId, nodeId, bucketId) ?: return DeleteBucketOutcome.Done // idempotent
        if (b.isPrimary) return DeleteBucketOutcome.Forbidden
        store.deleteBucketReparent(bucketId)
        return DeleteBucketOutcome.Done
    }

    /**
     * Move media into a target bucket WITHIN one owner-root (null = that owner's primary). The store derives the
     * shared owner from the source media and requires the target to match — so a member may re-bucket a
     * contributor's media, but a cross-owner move (or a target of a different owner) → Forbidden (403).
     */
    fun moveMedia(userId: String, scopeId: String, nodeId: String, mediaIds: List<String>, targetBucketId: String?): MoveMediaOutcome {
        if (!store.isMember(userId, scopeId)) return MoveMediaOutcome.NoNode
        if (store.findNode(scopeId, nodeId) == null) return MoveMediaOutcome.NoNode
        val ok = store.moveMedia(scopeId, nodeId, mediaIds, targetBucketId)
        return if (ok) MoveMediaOutcome.Done else MoveMediaOutcome.Forbidden
    }
}
