package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.blob.BlobUploadHandler
import me.raddatz.veyq.core.blob.BlobUploadStore
import me.raddatz.veyq.core.blob.CompleteItem
import me.raddatz.veyq.core.blob.CompleteItemResult
import me.raddatz.veyq.core.blob.UploadCompletions
import me.raddatz.veyq.core.blob.UploadOpener
import me.raddatz.veyq.core.http.FailureStatus.FORBIDDEN
import me.raddatz.veyq.core.http.FailureStatus.NOT_FOUND
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.items.ItemsCursor
import me.raddatz.veyq.core.items.ItemsRead
import me.raddatz.veyq.core.items.NodeItemsRevisions
import me.raddatz.veyq.core.membership.AccessMode.READ
import me.raddatz.veyq.core.membership.AccessMode.WRITE
import me.raddatz.veyq.core.membership.ScopeAccess
import me.raddatz.veyq.core.membership.refusal
import me.raddatz.veyq.core.persistence.createOrConflict
import jakarta.enterprise.context.ApplicationScoped

sealed interface CompleteOutcome {
    data class Results(val items: List<CompleteItemResult>) : CompleteOutcome
    object Suspended : CompleteOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : CompleteOutcome, HttpFailure by Failures.scopeDeleting
    object NoScope : CompleteOutcome, HttpFailure by Failures.scopeNotFound
}

sealed interface ListMediaOutcome {
    /** A conditional-GET page (cr-0107, ADR 0033): the keyset delta + both revisions + the id-set — the latter present **only when a delete advanced `delete_revision`** past the client's `sinceDelete`. */
    object Suspended : ListMediaOutcome, HttpFailure by Failures.tenantSuspended
    data class Page(
        val rows: List<MediaRow>,
        val mediaRevision: Int,
        val deleteRevision: Int,
        val mediaIds: List<String>?,
        /** The node's sealed drop private key and the wrapped key of every WRITE_ONLY identity among [rows]' owners:
         *  what opens a row a WRITE_ONLY guest added, served with the rows (ADR 0031 point 7). */
        val sealedDropPrivateKey: String? = null,
        val uploaderKeys: Map<String, String> = emptyMap(),
    ) : ListMediaOutcome
    /** The client's `If-None-Match` equals the node's item revision — nothing changed (a 304, no media query ran). */
    object NotModified : ListMediaOutcome
    object NoNode : ListMediaOutcome, HttpFailure by Failures.nodeNotFound
}

sealed interface MediaExifOutcome {
    /** The media's sealed panel bundle (opaque base64), or null when none was sealed. */
    object Suspended : MediaExifOutcome, HttpFailure by Failures.tenantSuspended
    data class Found(val wrappedExif: String?) : MediaExifOutcome
    object NoMedia : MediaExifOutcome, HttpFailure by httpFailure("media_not_found", NOT_FOUND)
}

sealed interface MetaOutcome {
    /** The media's sealed listed-metadata blob (opaque base64), or null when none/cleared. */
    object Suspended : MetaOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : MetaOutcome, HttpFailure by Failures.scopeDeleting
    data class Found(val wrappedMeta: String?) : MetaOutcome
    object NoMedia : MetaOutcome, HttpFailure by httpFailure("media_not_found", NOT_FOUND)
    /** The tier moved past the stated revision; nothing was written (ADR 0077). Carries the current tier + revision. */
    data class Stale(val wrappedMeta: String?, val metaRevision: Long) : MetaOutcome, HttpFailure by Failures.staleRevision
}

sealed interface DeleteMediaOutcome {
    /** The media is trashed (or was already trashed — idempotent). */
    object Suspended : DeleteMediaOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : DeleteMediaOutcome, HttpFailure by Failures.scopeDeleting
    object Done : DeleteMediaOutcome
    /** No such scope/media, or the caller holds no grant (a 404, no reveal). */
    object NoMedia : DeleteMediaOutcome, HttpFailure by httpFailure("media_not_found", NOT_FOUND)
}

sealed interface RestoreMediaOutcome {
    /** The media is restored (or was already live — idempotent). */
    object Suspended : RestoreMediaOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : RestoreMediaOutcome, HttpFailure by Failures.scopeDeleting
    object Done : RestoreMediaOutcome
    /** No such scope/media (e.g. already purged), or the caller holds no grant (a 404). */
    object NoMedia : RestoreMediaOutcome, HttpFailure by httpFailure("media_not_found", NOT_FOUND)
}

sealed interface PurgeMediaOutcome {
    /** The trashed media was permanently deleted. */
    object Suspended : PurgeMediaOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : PurgeMediaOutcome, HttpFailure by Failures.scopeDeleting
    object Done : PurgeMediaOutcome
    /** No such trashed media (unknown / already purged / still live), or no grant (a 404). */
    object NoMedia : PurgeMediaOutcome, HttpFailure by httpFailure("media_not_found", NOT_FOUND)
}

sealed interface ListBucketsOutcome {
    /** [sealedDropPrivateKey]: the node's, which opens a WRITE_ONLY primary's name (ADR 0031 point 7). */
    data class Ok(val buckets: List<BucketRow>, val sealedDropPrivateKey: String? = null) : ListBucketsOutcome
    object Suspended : ListBucketsOutcome, HttpFailure by Failures.tenantSuspended
    /** No such scope/node, or the caller holds no grant (a 404, no reveal). */
    object NoNode : ListBucketsOutcome, HttpFailure by Failures.nodeNotFound
}

sealed interface CreateBucketOutcome {
    data class Ok(val bucket: BucketRow) : CreateBucketOutcome
    object Suspended : CreateBucketOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : CreateBucketOutcome, HttpFailure by Failures.scopeDeleting
    /** A given parentBucketId is missing or not a primary (sub-buckets nest one level) — a 403. */
    object Forbidden : CreateBucketOutcome, HttpFailure by httpFailure("illegal_target", FORBIDDEN)
    /** No such scope/node, or the caller holds no grant (a 404). */
    object NoNode : CreateBucketOutcome, HttpFailure by Failures.nodeNotFound
    object IdTaken : CreateBucketOutcome, HttpFailure by Failures.idTaken
}

sealed interface RenameBucketOutcome {
    data class Ok(val bucket: BucketRow) : RenameBucketOutcome
    object Suspended : RenameBucketOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : RenameBucketOutcome, HttpFailure by Failures.scopeDeleting
    /** No such scope/node/bucket, or the caller holds no grant (a 404). */
    object NoBucket : RenameBucketOutcome, HttpFailure by httpFailure("bucket_not_found", NOT_FOUND)
    /** The bucket is a primary (not renameable) — a 403. */
    object Forbidden : RenameBucketOutcome, HttpFailure by httpFailure("illegal_target", FORBIDDEN)
}

sealed interface DeleteBucketOutcome {
    /** The sub-bucket was deleted + its media reparented (or it did not exist — idempotent). */
    object Suspended : DeleteBucketOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : DeleteBucketOutcome, HttpFailure by Failures.scopeDeleting
    object Done : DeleteBucketOutcome
    /** No such scope/node, or the caller holds no grant (a 404). */
    object NoNode : DeleteBucketOutcome, HttpFailure by Failures.nodeNotFound
    /** The bucket is a primary (not deletable) — a 403. */
    object Forbidden : DeleteBucketOutcome, HttpFailure by httpFailure("illegal_target", FORBIDDEN)
}

sealed interface MoveMediaOutcome {
    /** The media were moved. */
    object Suspended : MoveMediaOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : MoveMediaOutcome, HttpFailure by Failures.scopeDeleting
    object Done : MoveMediaOutcome
    /** No such scope/node, or the caller holds no grant (a 404). */
    object NoNode : MoveMediaOutcome, HttpFailure by Failures.nodeNotFound
    /** A medium or the target bucket is not the caller's own — a 403. */
    object Forbidden : MoveMediaOutcome, HttpFailure by httpFailure("illegal_target", FORBIDDEN)
}

/**
 * Use-case logic for the Memories media (en.memory.media, cr-0028): the member's completion of an upload, the
 * gallery listing, meta, trash, covers and buckets. Operator-blind, batch-shaped, per-item results. Scope access is
 * the one gate, [ScopeAccess], asked first (uc.spaces.scope-access): a non-member sees a top-level 404, not a reveal.
 * The generic upload — start, windows, verify, download — is core's [BlobUploadHandler]; what a completed upload
 * becomes is [MediaUploadCompletion], reached through core's [UploadCompletions] dispatcher.
 */
@ApplicationScoped
class MediaHandler(
    private val store: MediaStore,
    private val blobs: BlobUploadStore,
    private val uploads: BlobUploadHandler,
    private val completions: UploadCompletions,
    private val access: ScopeAccess,
    private val itemsRevisions: NodeItemsRevisions,
) {
    /** The member's `completeMedia`: the access check, then core's dispatcher, attributing each item to [userId]. */
    fun complete(userId: String, scopeId: String, items: List<CompleteItem>): CompleteOutcome {
        access.check(userId, scopeId, WRITE)
            .refusal(CompleteOutcome.NoScope, CompleteOutcome.Suspended, CompleteOutcome.Deleting)?.let { return it }
        return CompleteOutcome.Results(completions.complete(scopeId, items, UploadOpener.Member(userId)))
    }

    /**
     * One `listNodeMedia` poll (cr-0107, ADR 0033). Core's `NodeItemsRevisions.read` reads the node's
     * revision + delete revision as a **consistent pair** (one row). When the client's [ifNoneMatch] still equals the revision ⇒ **NotModified**
     * (a 304 — no media query, the idle fast path). Otherwise a **keyset delta** past the [since] cursor, plus both
     * revisions and — **only when [sinceDelete] lags `delete_revision`** (a real delete since) — the current
     * `mediaId` set for delete-reconcile. Non-member / unknown node ⇒ NoNode (a 404, no reveal).
     */
    fun listMedia(
        userId: String,
        scopeId: String,
        nodeId: String,
        ifNoneMatch: Int?,
        since: ItemsCursor?,
        sinceDelete: Int,
        trashed: Boolean,
    ): ListMediaOutcome {
        access.check(userId, scopeId, READ)
            .refusal(ListMediaOutcome.NoNode, ListMediaOutcome.Suspended)?.let { return it }
        val node = store.findNode(scopeId, nodeId) ?: return ListMediaOutcome.NoNode
        // The Trash tab (cr-0110): a plain full list of the node's trashed media (carrying deletedAt/deletedBy), no
        // conditional-GET/delta — the trash isn't live-polled, so the ETag/since/id-set machinery doesn't apply.
        if (trashed) {
            val rows = store.listNodeTrashed(scopeId, nodeId)
            val (revision, deleteRevision) = itemsRevisions.revisions(nodeId)
            return ListMediaOutcome.Page(
                rows, revision, deleteRevision, null, node.sealedDropPrivateKey, store.uploaderKeys(rows),
            )
        }
        // The platform's conditional read (core's NodeItemsRevisions, inbox #410): compare -> 304, else Memories' own
        // keyset query, and the id set only when a delete advanced past the client's sinceDelete.
        return when (
            val r = itemsRevisions.read(
                nodeId, ifNoneMatch, sinceDelete,
                delta = { store.listNodeMediaSince(scopeId, nodeId, since) },
                ids = { store.listNodeMediaIds(scopeId, nodeId) },
            )
        ) {
            ItemsRead.NotModified -> ListMediaOutcome.NotModified
            is ItemsRead.Changed -> ListMediaOutcome.Page(
                r.delta, r.revisions.revision, r.revisions.deleteRevision, r.ids, node.sealedDropPrivateKey, store.uploaderKeys(r.delta),
            )
        }
    }

    /** The media's lazy, panel-only tier for the detail panel. Non-member or unknown media → NoMedia (a 404, no reveal). */
    fun mediaExif(userId: String, scopeId: String, mediaId: String): MediaExifOutcome {
        access.check(userId, scopeId, READ)
            .refusal(MediaExifOutcome.NoMedia, MediaExifOutcome.Suspended)?.let { return it }
        val media = store.findMedia(scopeId, mediaId) ?: return MediaExifOutcome.NoMedia
        return MediaExifOutcome.Found(media.wrappedExif)
    }

    /** Overwrite the media's sealed listed-metadata blob (opaque re-seal, e.g. a corrected date), revision-checked. */
    fun updateMeta(userId: String, scopeId: String, mediaId: String, wrappedMeta: String?, metaRevision: Long): MetaOutcome {
        access.check(userId, scopeId, WRITE)
            .refusal(MetaOutcome.NoMedia, MetaOutcome.Suspended, MetaOutcome.Deleting)?.let { return it }
        return when (val r = store.setMeta(scopeId, mediaId, wrappedMeta, metaRevision)) {
            is MediaStore.SetMetaResult.Written -> MetaOutcome.Found(r.wrappedMeta)
            MediaStore.SetMetaResult.Missing -> MetaOutcome.NoMedia
            is MediaStore.SetMetaResult.Stale -> MetaOutcome.Stale(r.wrappedMeta, r.metaRevision)
        }
    }

    /**
     * Trash a media (uc.memory.delete-media, cr-0110): **any member** of the scope may soft-delete it into the
     * per-Memory trash (reversible; the irreversible purge is MediaTrashPurge's). Non-member or unknown media →
     * NoMedia (a 404, no reveal). Idempotent.
     */
    fun deleteMedia(userId: String, scopeId: String, mediaId: String): DeleteMediaOutcome {
        access.check(userId, scopeId, WRITE)
            .refusal(DeleteMediaOutcome.NoMedia, DeleteMediaOutcome.Suspended, DeleteMediaOutcome.Deleting)?.let { return it }
        return if (store.trashMedia(scopeId, mediaId, userId) != null) DeleteMediaOutcome.Done else DeleteMediaOutcome.NoMedia
    }

    /** Restore a trashed media (cr-0110): any member may bring it back before the purge. 404 on unknown/purged. */
    fun restoreMedia(userId: String, scopeId: String, mediaId: String): RestoreMediaOutcome {
        access.check(userId, scopeId, WRITE)
            .refusal(RestoreMediaOutcome.NoMedia, RestoreMediaOutcome.Suspended, RestoreMediaOutcome.Deleting)?.let { return it }
        return if (store.restoreMedia(scopeId, mediaId) != null) RestoreMediaOutcome.Done else RestoreMediaOutcome.NoMedia
    }

    /**
     * Permanently delete a trashed media NOW (uc.memory.delete-media, cr-0111): any member may expedite the purge
     * of an already-trashed item (the security-model reversible/irreversible split). Non-member, unknown, or a
     * still-LIVE media → NoMedia (a 404). The blob objects go on the reclaim list with the rows.
     */
    fun purgeMedia(userId: String, scopeId: String, mediaId: String): PurgeMediaOutcome {
        access.check(userId, scopeId, WRITE)
            .refusal(PurgeMediaOutcome.NoMedia, PurgeMediaOutcome.Suspended, PurgeMediaOutcome.Deleting)?.let { return it }
        if (!store.purgeMedia(scopeId, mediaId)) return PurgeMediaOutcome.NoMedia
        return PurgeMediaOutcome.Done
    }

    // --- buckets (uc.memory.buckets, ADR 0034, cr-0113 · curation relaxed cr-0123) — member-gated: a member may
    // curate any owner-root's SUB-buckets (create/rename/delete) + move media WITHIN one root, but never a primary
    // and never across owners (attribution — the owner_key — is preserved on every path) ---

    /** List the node's uploader buckets for the "Beiträge" tab. Non-member / unknown node → NoNode (404, no reveal). */
    fun listBuckets(userId: String, scopeId: String, nodeId: String): ListBucketsOutcome {
        access.check(userId, scopeId, READ)
            .refusal(ListBucketsOutcome.NoNode, ListBucketsOutcome.Suspended)?.let { return it }
        val node = store.findNode(scopeId, nodeId) ?: return ListBucketsOutcome.NoNode
        return ListBucketsOutcome.Ok(store.listBuckets(nodeId), node.sealedDropPrivateKey)
    }

    /**
     * Create a sub-bucket under a primary root. `parentBucketId == null` ⇒ the caller's own primary; else it must
     * be a **primary** (any owner's — so a member can add sub-buckets under a contributor's root), and the new sub
     * inherits that root's owner_key. A missing/non-primary parent → Forbidden (403). Non-member / unknown node → NoNode (404).
     */
    fun createBucket(userId: String, scopeId: String, nodeId: String, bucketId: String, sealedName: String, parentBucketId: String?): CreateBucketOutcome {
        access.check(userId, scopeId, WRITE)
            .refusal(CreateBucketOutcome.NoNode, CreateBucketOutcome.Suspended, CreateBucketOutcome.Deleting)?.let { return it }
        if (store.findNode(scopeId, nodeId) == null) return CreateBucketOutcome.NoNode
        val ownerKey = if (parentBucketId != null) {
            val parent = store.findBucket(scopeId, nodeId, parentBucketId) ?: return CreateBucketOutcome.Forbidden
            if (!parent.isPrimary) return CreateBucketOutcome.Forbidden // nest one level, under a primary only
            parent.ownerKey
        } else {
            OwnerKey.User(userId).format()
        }
        // The client-minted id (ADR 0072): the sealed name is bound to it.
        return createOrConflict(conflict = { if (store.bucketIdTaken(bucketId)) CreateBucketOutcome.IdTaken else null }) {
            store.createSubBucket(bucketId, scopeId, nodeId, ownerKey, sealedName)
                ?.let { CreateBucketOutcome.Ok(it) } ?: CreateBucketOutcome.IdTaken
        }
    }

    /** Rename a sub-bucket (any member may — incl. a contributor's, cr-0123). A primary is not renameable (Forbidden → 403). */
    fun renameBucket(userId: String, scopeId: String, nodeId: String, bucketId: String, sealedName: String): RenameBucketOutcome {
        access.check(userId, scopeId, WRITE)
            .refusal(RenameBucketOutcome.NoBucket, RenameBucketOutcome.Suspended, RenameBucketOutcome.Deleting)?.let { return it }
        // ADR 0061: the bucket lookup is keyed on (scope, node, bucket) and never touches the node row, so the
        // node gate every sibling op passes through has to be spelled out here — else a trashed subtree's
        // buckets stay writable while everything else about it is hidden.
        if (store.findNode(scopeId, nodeId) == null) return RenameBucketOutcome.NoBucket
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
        access.check(userId, scopeId, WRITE)
            .refusal(DeleteBucketOutcome.NoNode, DeleteBucketOutcome.Suspended, DeleteBucketOutcome.Deleting)?.let { return it }
        if (store.findNode(scopeId, nodeId) == null) return DeleteBucketOutcome.NoNode // ADR 0061, as in renameBucket
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
        access.check(userId, scopeId, WRITE)
            .refusal(MoveMediaOutcome.NoNode, MoveMediaOutcome.Suspended, MoveMediaOutcome.Deleting)?.let { return it }
        if (store.findNode(scopeId, nodeId) == null) return MoveMediaOutcome.NoNode
        val ok = store.moveMedia(scopeId, nodeId, mediaIds, targetBucketId)
        return if (ok) MoveMediaOutcome.Done else MoveMediaOutcome.Forbidden
    }
}
