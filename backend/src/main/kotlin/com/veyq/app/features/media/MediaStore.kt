package com.veyq.app.features.media

import com.veyq.app.features.content.NodeEntity
import com.veyq.app.features.sharing.ShareIdentityEntity
import com.veyq.app.shared.blobstore.BlobStore.Companion.chunkKey
import com.veyq.app.shared.membership.MembershipEntity
import com.veyq.app.shared.membership.MembershipStore
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.UUID

/** A node's media for read-back: the original blob's metadata + wrap, plus its role-tagged derivative tiers. */
data class MediaRow(
    val mediaId: String,
    val blobId: String,
    val chunkCount: Int,
    val byteSize: Long,
    val wrappedBlobKey: String,
    val manifest: List<Long>?,
    val derivatives: List<DerivativeRow>,
    val wrappedMeta: String?,
    /** When this media last changed (cr-0107): `= createdAt` at attach, `now` on a meta edit — the keyset cursor sort key. */
    val updatedAt: Instant,
    /** When this media was trashed (cr-0110), or null for a live media; populated only in the trashed listing. */
    val deletedAt: Instant? = null,
    /** Who trashed this media (cr-0110, the audit), or null for a live media; populated only in the trashed listing. */
    val deletedBy: String? = null,
    /** cr-0113: the uploader's opaque owner_key ("user:{id}"|"share:{id}"), or null for a pre-buckets member row. */
    val ownerKey: String? = null,
    /** cr-0113: the bucket this media lives in, or null = the uploader's primary. */
    val bucketId: String? = null,
)

/** The gallery's keyset cursor (cr-0107, ADR 0033): the newest `(updatedAt, mediaId)` the client has already seen. */
data class MediaCursor(val updatedAt: Instant, val mediaId: String)

/** One role-tagged derivative tier of a media (its own blob metadata + wrap). */
data class DerivativeRow(
    val kind: String,
    val blobId: String,
    val chunkCount: Int,
    val byteSize: Long,
    val wrappedBlobKey: String,
    val manifest: List<Long>?,
)

/** A verified derivative upload to attach: its role + session + the verified per-chunk manifest. */
data class DerivativeAttach(val kind: String, val session: UploadSessionEntity, val manifest: List<Long>?)

/** A committed blob's opaque read-back ref (cr-0073): the same shape a gallery tier carries, reused for the cover. */
data class BlobRefData(
    val blobId: String,
    val chunkCount: Int,
    val byteSize: Long,
    val wrappedBlobKey: String,
    val manifest: List<Long>?,
)

/** The outcome of committing a cover blob: the new ref + the superseded prior blob's chunk keys to purge from the store. */
data class CoverCommitRows(val ref: BlobRefData, val reclaimKeys: List<String>)

/** A node's uploader bucket for the "Beiträge" tab (en.memory.buckets, cr-0113): opaque owner_key + structure + count. */
data class BucketRow(
    val bucketId: String,
    val ownerKey: String,
    val parentBucketId: String?,
    val isPrimary: Boolean,
    val sealedName: String?,
    val mediaCount: Int,
)

/**
 * Persistence for media blobs (cr-0028). Operator-blind: stores ciphertext wraps + opaque metadata only.
 * Scope access = the caller holds a membership for the scope (cr-0026). The manifest (per-chunk ciphertext
 * sizes) is held as a small JSON array of integers — itself only ciphertext-size metadata.
 */
@ApplicationScoped
class MediaStore(private val membership: MembershipStore) {

    fun isMember(userId: String, scopeId: String): Boolean = membership.isScopeMember(userId, scopeId)

    /** The node, if it exists in this scope (its `kind` decides the media-target rule). */
    fun findNode(scopeId: String, nodeId: String): NodeEntity? =
        NodeEntity.findById(nodeId)?.takeIf { it.scopeId == scopeId }

    @Transactional
    fun createSession(scopeId: String, storageKey: String, chunkCount: Int, byteSize: Long, wrappedBlobKey: String): String {
        val id = UUID.randomUUID().toString()
        UploadSessionEntity().apply {
            this.id = id
            this.scopeId = scopeId
            this.storageKey = storageKey
            this.chunkCount = chunkCount
            this.byteSize = byteSize
            this.wrappedBlobKey = wrappedBlobKey
            createdAt = Instant.now()
        }.persist()
        return id
    }

    fun findSession(scopeId: String, uploadId: String): UploadSessionEntity? =
        UploadSessionEntity.findById(uploadId)?.takeIf { it.scopeId == scopeId }

    /**
     * Promote a media's verified sessions to blobs + attach to the node, consuming every session. The original
     * session becomes `veyq_media.blob_id`; each derivative becomes its own blob + a `veyq_media_derivative`
     * row. One transaction — all blobs, the media, and the derivative rows commit together. Returns
     * (originalBlobId, mediaId).
     */
    @Transactional
    fun attach(
        original: UploadSessionEntity,
        nodeId: String,
        originalManifest: List<Long>?,
        derivatives: List<DerivativeAttach>,
        wrappedMeta: String?,
        wrappedExif: String?,
        shareIdentityId: String? = null, // cr-0101: the contributor identity, when attached via a CONTRIBUTOR share
        uploaderUserId: String? = null,  // cr-0113: the member uploader (member path); null on the contributor path
        targetBucketId: String? = null,  // cr-0113: an explicit target bucket; null = the uploader's primary
    ): Pair<String, String> {
        val scopeId = original.scopeId
        val ownerKey = ownerKeyFor(uploaderUserId, shareIdentityId)
        val bucket = resolveAttachBucket(scopeId, nodeId, ownerKey, targetBucketId)
        val originalBlobId = persistBlob(original, originalManifest)
        val mediaId = UUID.randomUUID().toString()
        val now = Instant.now()
        MediaEntity().apply {
            id = mediaId
            this.scopeId = scopeId
            this.nodeId = nodeId
            this.blobId = originalBlobId
            this.wrappedMeta = wrappedMeta
            this.wrappedExif = wrappedExif
            this.shareIdentityId = shareIdentityId
            this.createdBy = ownerKey // cr-0113: the buckets attribution (denormalized owner_key)
            this.bucketId = bucket.id // cr-0113: the resolved primary (or explicit) bucket
            createdAt = now
            updatedAt = now // cr-0107: the keyset cursor starts at creation time
        }.persist()
        for (d in derivatives) {
            val derivBlobId = persistBlob(d.session, d.manifest)
            MediaDerivativeEntity().apply {
                id = UUID.randomUUID().toString()
                this.mediaId = mediaId
                kind = d.kind
                blobId = derivBlobId
                createdAt = Instant.now()
            }.persist()
        }
        UploadSessionEntity.deleteById(original.id)
        derivatives.forEach { UploadSessionEntity.deleteById(it.session.id) }
        bumpMediaRevision(nodeId) // cr-0107: an add moves media_revision (the live-updates change signal), not delete_revision
        return originalBlobId to mediaId
    }

    /**
     * Bump the node's live-updates counters (cr-0107, ADR 0033) in **one atomic UPDATE** on the `veyq_node` row,
     * so `media_revision` + `delete_revision` always read as a **consistent pair**. Every gallery-media mutation
     * moves `media_revision` (the conditional-GET change signal); a real delete additionally moves
     * `delete_revision` ([deleted] = true), which gates the O(N) id-set delete-reconcile. A cover change calls
     * neither. Joins the caller's transaction (attach/setMeta self-invoke within their `@Transactional`; the
     * share delete-own path calls it within its own). Opaque operational metadata — operator-blind.
     */
    @Transactional
    fun bumpMediaRevision(nodeId: String, deleted: Boolean = false) {
        if (deleted) {
            NodeEntity.update("mediaRevision = mediaRevision + 1, deleteRevision = deleteRevision + 1 where id = ?1", nodeId)
        } else {
            NodeEntity.update("mediaRevision = mediaRevision + 1 where id = ?1", nodeId)
        }
    }

    /** Persist a blob row from a verified session; returns the new blob id. */
    private fun persistBlob(session: UploadSessionEntity, manifest: List<Long>?): String {
        val blobId = UUID.randomUUID().toString()
        BlobEntity().apply {
            id = blobId
            scopeId = session.scopeId
            storageKey = session.storageKey
            chunkCount = session.chunkCount
            byteSize = session.byteSize
            wrappedBlobKey = session.wrappedBlobKey
            this.manifest = manifest?.let { encodeManifest(it) }
            createdAt = Instant.now()
        }.persist()
        return blobId
    }

    /**
     * The node's media as a **keyset delta** past [since] (cr-0107, ADR 0033): rows whose `updated_at` is strictly
     * greater than the cursor (id as the tie-break), oldest-first so the client advances its cursor to the last
     * row. [since] = null ⇒ the full list (first load). Ordered by `(updated_at, id)`, the cursor's sort key.
     */
    fun listNodeMediaSince(scopeId: String, nodeId: String, since: MediaCursor?): List<MediaRow> {
        val media = if (since == null) {
            MediaEntity.find("scopeId = ?1 and nodeId = ?2 and deletedAt is null order by updatedAt, id", scopeId, nodeId).list()
        } else {
            MediaEntity.find(
                "scopeId = ?1 and nodeId = ?2 and deletedAt is null and (updatedAt > ?3 or (updatedAt = ?3 and id > ?4)) order by updatedAt, id",
                scopeId, nodeId, since.updatedAt, since.mediaId,
            ).list()
        }
        return media.mapNotNull(::toRow)
    }

    /**
     * Every current `mediaId` of the node (ids only) — the delete-reconcile set (cr-0107). O(N), so the handler
     * asks for it **only when `delete_revision` advanced** (a real removal); the client drops any cached id it no
     * longer sees, catching a delete and a net-zero delete+add.
     */
    fun listNodeMediaIds(scopeId: String, nodeId: String): List<String> =
        MediaEntity.find("scopeId = ?1 and nodeId = ?2 and deletedAt is null", scopeId, nodeId).list().map { it.id }

    /**
     * Soft-delete (trash) a media in this scope (uc.memory.delete-media, cr-0110): set `deleted_at`/`deleted_by`
     * (the audit) and bump BOTH counters (`bumpMediaRevision(deleted = true)`) so an open gallery drops the tile
     * via the id-set reconcile (cr-0107). Idempotent — an already-trashed media is a no-op (no re-bump). Returns
     * the media's nodeId, or null when no such media exists in the scope (⇒ a non-enumerable 404).
     */
    @Transactional
    fun trashMedia(scopeId: String, mediaId: String, userId: String): String? {
        val media = MediaEntity.findById(mediaId)?.takeIf { it.scopeId == scopeId } ?: return null
        if (media.deletedAt != null) return media.nodeId // already trashed — idempotent
        media.deletedAt = Instant.now()
        media.deletedBy = userId
        media.persist()
        bumpMediaRevision(media.nodeId, deleted = true)
        return media.nodeId
    }

    /**
     * Restore a trashed media (cr-0110): clear `deleted_at`/`deleted_by`, advance `updated_at` so it re-surfaces
     * in the live keyset delta, and bump `media_revision` (an add-back, not a delete). Idempotent on a live media.
     * Returns the nodeId, or null when no such media in the scope (e.g. already purged) ⇒ a 404.
     */
    @Transactional
    fun restoreMedia(scopeId: String, mediaId: String): String? {
        val media = MediaEntity.findById(mediaId)?.takeIf { it.scopeId == scopeId } ?: return null
        if (media.deletedAt == null) return media.nodeId // already live — idempotent
        media.deletedAt = null
        media.deletedBy = null
        media.updatedAt = Instant.now() // re-surface via the keyset delta (cr-0107)
        media.persist()
        bumpMediaRevision(media.nodeId)
        return media.nodeId
    }

    /** The node's TRASHED media, newest-trashed first (cr-0110) — the Trash tab list (rows carry deletedAt/deletedBy). */
    fun listNodeTrashed(scopeId: String, nodeId: String): List<MediaRow> =
        MediaEntity.find("scopeId = ?1 and nodeId = ?2 and deletedAt is not null order by deletedAt desc, id", scopeId, nodeId)
            .list().mapNotNull(::toRow)

    /**
     * Hard-delete one media's rows in FK order (derivative → media → blob) and return the store chunk keys to
     * reclaim — the single shared body behind the retention purge, the member-expedited purge and the
     * contributor delete-own. The caller owns the `@Transactional` boundary, the best-effort post-commit
     * `blobStore.delete` of the returned keys, and any revision bump (only a LIVE-media removal bumps a revision;
     * a trashed row is already hidden from every list, so the two trash purges don't).
     */
    fun purgeMediaRows(media: MediaEntity): List<String> {
        val derivatives = MediaDerivativeEntity.find("mediaId = ?1", media.id).list()
        val blobIds = listOf(media.blobId) + derivatives.map { it.blobId }
        derivatives.forEach { MediaDerivativeEntity.deleteById(it.id) } // FK: the referencing rows go first
        MediaEntity.deleteById(media.id)
        return blobIds.flatMap { deleteBlobRow(it) }
    }

    /**
     * Hard-delete a single **trashed** media now (cr-0111, the member-expedited purge). Only a trashed media
     * (`deletedAt != null`) is purgeable — a live or unknown media returns null (⇒ a 404). No revision bump: a
     * trashed row is already excluded from every list. The blob-object purge is the caller's (best-effort).
     */
    @Transactional
    fun purgeMedia(scopeId: String, mediaId: String): List<String>? {
        val media = MediaEntity.findById(mediaId)?.takeIf { it.scopeId == scopeId && it.deletedAt != null } ?: return null
        return purgeMediaRows(media)
    }

    /**
     * Hard-delete every media trashed before [cutoff] — the retention purge (cr-0110, MediaTrashPurge), across
     * all scopes. NO revision bump: trashed rows are already excluded from every list, so no client ever saw
     * them. The blob-object purge is the caller's (best-effort, after this tx).
     */
    @Transactional
    fun purgeTrashedBefore(cutoff: Instant): List<String> {
        val expired = MediaEntity.find("deletedAt is not null and deletedAt < ?1", cutoff).list()
        return expired.flatMap { purgeMediaRows(it) }
    }

    /**
     * Hard-delete every OPEN upload session created before [cutoff], across all scopes, and return the chunk
     * keys its client may already have staged in the object store (uc.memory.upload E2 / en.memory.media E3).
     * The row is the **only** pointer to its `storage_key`, so dropping it without the keys orphans those
     * objects permanently — the caller must purge them (best-effort, after this tx commits). Two sources feed
     * this: an upload cancelled or killed mid-flight, and a session the `complete` budget gate rejected
     * ([MediaHandler] purges that one's objects but leaves the row). Keys are enumerated for the full declared
     * `chunk_count`; an abandoned session has an unknown number of them actually present, so the store delete
     * must tolerate absent keys — as the budget path already does.
     */
    @Transactional
    fun purgeSessionsStartedBefore(cutoff: Instant): List<String> {
        val stale = UploadSessionEntity.find("createdAt < ?1", cutoff).list()
        val reclaimKeys = stale.flatMap { s -> (0 until s.chunkCount).map { chunkKey(s.storageKey, it) } }
        stale.forEach { UploadSessionEntity.deleteById(it.id) }
        return reclaimKeys
    }

    /** Map a media entity to its read-back row, resolving the original + derivative blobs; null when the original blob is missing. */
    private fun toRow(m: MediaEntity): MediaRow? {
        val blob = BlobEntity.findById(m.blobId) ?: return null
        val derivatives = MediaDerivativeEntity.find("mediaId = ?1", m.id).list().mapNotNull { d ->
            val derivBlob = BlobEntity.findById(d.blobId) ?: return@mapNotNull null
            DerivativeRow(d.kind, derivBlob.id, derivBlob.chunkCount, derivBlob.byteSize, derivBlob.wrappedBlobKey, decodeManifest(derivBlob.manifest))
        }
        return MediaRow(
            m.id, blob.id, blob.chunkCount, blob.byteSize, blob.wrappedBlobKey, decodeManifest(blob.manifest),
            derivatives, m.wrappedMeta, m.updatedAt ?: m.createdAt, // cr-0107: coalesce for pre-backfill safety
            m.deletedAt, m.deletedBy, // cr-0110: null for live media, populated in the trashed listing
            m.createdBy ?: m.shareIdentityId?.let { OwnerKey.Share(it).format() }, m.bucketId, // cr-0113
        )
    }

    /** The media, if it exists in this scope — carries the lazy `wrappedExif` for the lightbox exif endpoint. */
    fun findMedia(scopeId: String, mediaId: String): MediaEntity? =
        MediaEntity.findById(mediaId)?.takeIf { it.scopeId == scopeId }

    /** Overwrite a media's sealed `wrappedMeta` verbatim (opaque; e.g. a re-sealed corrected date). */
    @Transactional
    fun setMeta(scopeId: String, mediaId: String, wrappedMeta: String?): MediaEntity? {
        val media = MediaEntity.findById(mediaId)?.takeIf { it.scopeId == scopeId } ?: return null
        media.wrappedMeta = wrappedMeta
        media.updatedAt = Instant.now() // cr-0107: the edit advances the keyset cursor, so the live poll re-fetches it
        media.persist()
        bumpMediaRevision(media.nodeId) // a meta edit moves media_revision, not delete_revision
        return media
    }

    fun findBlob(scopeId: String, blobId: String): BlobEntity? =
        BlobEntity.findById(blobId)?.takeIf { it.scopeId == scopeId }

    /**
     * Commit a verified session as the node's **standalone cover** blob (cr-0073): persist one `veyq_blob`
     * (NO `veyq_media` row, so it never lists in the gallery), point the node's `cover_blob_id` at it, and
     * **atomically reclaim** any prior cover blob (its row is deleted here; its store objects are purged by the
     * caller via the returned chunk keys). Consumes the upload session. Returns the new ref + the reclaim keys.
     */
    @Transactional
    fun commitCover(nodeId: String, session: UploadSessionEntity, manifest: List<Long>?): CoverCommitRows {
        val node = NodeEntity.findById(nodeId)!! // resolved by the handler before verify; reloaded in-tx
        val reclaimKeys = deleteBlobRow(node.coverBlobId)
        val blobId = persistBlob(session, manifest)
        node.coverBlobId = blobId
        node.coverByteSize = session.byteSize
        node.persist()
        UploadSessionEntity.deleteById(session.id)
        return CoverCommitRows(
            BlobRefData(blobId, session.chunkCount, session.byteSize, session.wrappedBlobKey, manifest),
            reclaimKeys,
        )
    }

    /**
     * Detach the node's cover blob (cr-0073): clear its `cover_blob_id`/`cover_byte_size` and delete the
     * `veyq_blob` row. Returns the blob's chunk keys for a best-effort store purge (empty when the node — or
     * scope — has no cover blob; idempotent). Null node ⇒ empty (the endpoint answers 204 either way).
     */
    @Transactional
    fun clearCover(scopeId: String, nodeId: String): List<String> {
        val node = NodeEntity.findById(nodeId)?.takeIf { it.scopeId == scopeId } ?: return emptyList()
        val reclaimKeys = deleteBlobRow(node.coverBlobId)
        node.coverBlobId = null
        node.coverByteSize = null
        node.persist()
        return reclaimKeys
    }

    /** Delete a blob row by id (if present) and return its chunk keys for a best-effort store purge (cover + trash purge). */
    private fun deleteBlobRow(blobId: String?): List<String> {
        val blob = blobId?.let { BlobEntity.findById(it) } ?: return emptyList()
        val keys = (0 until blob.chunkCount).map { chunkKey(blob.storageKey, it) }
        BlobEntity.deleteById(blob.id)
        return keys
    }

    // Manifest = a JSON array of per-chunk ciphertext sizes; tiny and integer-only, so encoded by hand.
    private fun encodeManifest(sizes: List<Long>): String = sizes.joinToString(",", "[", "]")

    private fun decodeManifest(s: String?): List<Long>? =
        s?.trim()?.removeSurrounding("[", "]")?.takeIf { it.isNotBlank() }?.split(",")?.map { it.trim().toLong() }

    // --- buckets (en.memory.buckets, ADR 0034, cr-0113) ----------------------

    /** The scope's owner userId (its sole/oldest membership) — the fallback attribution for pre-cr0113 member media. */
    fun scopeOwnerUserId(scopeId: String): String? =
        MembershipEntity.find("scopeId = ?1", scopeId).firstResult()?.userId

    /** A media's owner_key: the denormalized `created_by`, else the contributor `share:{id}`, else the scope owner. */
    private fun ownerKeyOf(m: MediaEntity, scopeOwnerUserId: String?): String =
        m.createdBy
            ?: m.shareIdentityId?.let { OwnerKey.Share(it).format() }
            ?: OwnerKey.User(scopeOwnerUserId ?: "unknown").format()

    /** The owner_key for a NEW upload: a contributor `share:{id}`, else a member `user:{id}`. */
    private fun ownerKeyFor(uploaderUserId: String?, shareIdentityId: String?): String =
        when {
            shareIdentityId != null -> OwnerKey.Share(shareIdentityId)
            uploaderUserId != null -> OwnerKey.User(uploaderUserId)
            else -> OwnerKey.User("unknown")
        }.format()

    /** A primary's sealed name: for a contributor (`share:{id}`) copy the identity's sealed name; else null (member → username). */
    private fun primarySealedNameFor(ownerKey: String): String? =
        when (val owner = OwnerKey.parse(ownerKey)) {
            is OwnerKey.Share -> ShareIdentityEntity.findById(owner.shareIdentityId)?.sealedDisplayName
            is OwnerKey.User -> null
        }

    /**
     * Find-or-create the uploader's PRIMARY bucket in a node (lazy, virtual-until-first-use). Primary uniqueness
     * `(node, owner_key)` is enforced HERE under the caller's transaction (no DB partial index in the H2 ∩ MariaDB
     * subset). Refreshes a still-missing sealed name (a contributor primary materialized before its identity name).
     */
    private fun ensurePrimaryBucket(scopeId: String, nodeId: String, ownerKey: String, sealedName: String?): BucketEntity {
        val existing = BucketEntity.find("nodeId = ?1 and ownerKey = ?2 and isPrimary = true", nodeId, ownerKey).firstResult()
        if (existing != null) {
            if (existing.sealedName == null && sealedName != null) { existing.sealedName = sealedName; existing.persist() }
            return existing
        }
        return BucketEntity().apply {
            id = UUID.randomUUID().toString()
            this.scopeId = scopeId
            this.nodeId = nodeId
            this.ownerKey = ownerKey
            parentBucketId = null
            this.sealedName = sealedName
            isPrimary = true
            createdAt = Instant.now()
        }.also { it.persist() }
    }

    /** Resolve the bucket a new media attaches to: an explicit caller-owned bucket in this node, else the uploader's primary. */
    private fun resolveAttachBucket(scopeId: String, nodeId: String, ownerKey: String, targetBucketId: String?): BucketEntity {
        if (targetBucketId != null) {
            BucketEntity.findById(targetBucketId)
                ?.takeIf { it.scopeId == scopeId && it.nodeId == nodeId && it.ownerKey == ownerKey }
                ?.let { return it }
        }
        return ensurePrimaryBucket(scopeId, nodeId, ownerKey, primarySealedNameFor(ownerKey))
    }

    /**
     * The node's buckets for the "Beiträge" tab (uc.memory.buckets). Materializes a primary per distinct uploader
     * among the live media and lazily backfills pre-cr0113 rows (sets their `created_by` + `bucket_id`), so every
     * live medium groups under a concrete bucket; returns each bucket (primaries first) with its live media count.
     */
    @Transactional
    fun listBuckets(scopeId: String, nodeId: String): List<BucketRow> {
        val scopeOwner = scopeOwnerUserId(scopeId)
        val liveMedia = MediaEntity.find("scopeId = ?1 and nodeId = ?2 and deletedAt is null", scopeId, nodeId).list()
        for (m in liveMedia) {
            val ownerKey = ownerKeyOf(m, scopeOwner)
            var dirty = false
            if (m.createdBy == null) { m.createdBy = ownerKey; dirty = true }
            if (m.bucketId == null) {
                m.bucketId = ensurePrimaryBucket(scopeId, nodeId, ownerKey, primarySealedNameFor(ownerKey)).id
                dirty = true
            }
            if (dirty) m.persist()
        }
        return BucketEntity.find("nodeId = ?1 order by isPrimary desc, createdAt", nodeId).list().map { b ->
            val count = MediaEntity.count("nodeId = ?1 and bucketId = ?2 and deletedAt is null", nodeId, b.id).toInt()
            BucketRow(b.id, b.ownerKey, b.parentBucketId, b.isPrimary, b.sealedName, count)
        }
    }

    /** Create a sub-bucket one level under `ownerKey`'s primary (materialized if needed) — the caller's own or,
     *  post-cr0123, a contributor root a member curates. */
    @Transactional
    fun createSubBucket(scopeId: String, nodeId: String, ownerKey: String, sealedName: String): BucketRow {
        val primary = ensurePrimaryBucket(scopeId, nodeId, ownerKey, primarySealedNameFor(ownerKey))
        val sub = BucketEntity().apply {
            id = UUID.randomUUID().toString()
            this.scopeId = scopeId
            this.nodeId = nodeId
            this.ownerKey = ownerKey
            parentBucketId = primary.id
            this.sealedName = sealedName
            isPrimary = false
            createdAt = Instant.now()
        }.also { it.persist() }
        return BucketRow(sub.id, sub.ownerKey, sub.parentBucketId, false, sub.sealedName, 0)
    }

    /** A bucket in this scope+node (for the handler's member-gated sub-bucket ops + primary check), or null. */
    fun findBucket(scopeId: String, nodeId: String, bucketId: String): BucketEntity? =
        BucketEntity.findById(bucketId)?.takeIf { it.scopeId == scopeId && it.nodeId == nodeId }

    /** Re-store a sub-bucket's sealed name (the handler validated own + non-primary). Returns the updated row. */
    @Transactional
    fun renameBucket(bucketId: String, sealedName: String): BucketRow? {
        val b = BucketEntity.findById(bucketId) ?: return null
        b.sealedName = sealedName
        b.persist()
        val count = MediaEntity.count("nodeId = ?1 and bucketId = ?2 and deletedAt is null", b.nodeId, b.id).toInt()
        return BucketRow(b.id, b.ownerKey, b.parentBucketId, b.isPrimary, b.sealedName, count)
    }

    /**
     * Delete a sub-bucket (the handler validated own + non-primary): reparent its media to the owner's primary,
     * then drop the row. **Media are never deleted** (destructive delete is the media trash path, cr-0110).
     */
    @Transactional
    fun deleteBucketReparent(bucketId: String) {
        val sub = BucketEntity.findById(bucketId) ?: return
        val primary = ensurePrimaryBucket(sub.scopeId, sub.nodeId, sub.ownerKey, primarySealedNameFor(sub.ownerKey))
        MediaEntity.update("bucketId = ?1 where bucketId = ?2", primary.id, bucketId)
        bumpMediaRevision(sub.nodeId) // cr-0113: reparent changed the media's bucketId → advance the ETag
        BucketEntity.deleteById(bucketId)
    }

    /**
     * Move media into a target bucket WITHIN one owner-root (null = that owner's primary). The owner is **derived
     * from the source media** — they must all share one `owner_key` (else a cross-owner move ⇒ false/403) — and the
     * target bucket must have that same owner. Every medium is in this node + live. Returns false (⇒ 403) on any
     * mismatch. The caller-is-member gate lives in the handler (cr-0123).
     */
    @Transactional
    fun moveMedia(scopeId: String, nodeId: String, mediaIds: List<String>, targetBucketId: String?): Boolean {
        val scopeOwner = scopeOwnerUserId(scopeId)
        val media = mediaIds.map { id ->
            MediaEntity.findById(id)?.takeIf { it.scopeId == scopeId && it.nodeId == nodeId && it.deletedAt == null } ?: return false
        }
        // One shared root only — a single distinct source owner_key; more than one would be a cross-owner move.
        val ownerKey = media.map { ownerKeyOf(it, scopeOwner) }.distinct().singleOrNull() ?: return false
        val target = if (targetBucketId == null) {
            ensurePrimaryBucket(scopeId, nodeId, ownerKey, primarySealedNameFor(ownerKey))
        } else {
            BucketEntity.findById(targetBucketId)
                ?.takeIf { it.scopeId == scopeId && it.nodeId == nodeId && it.ownerKey == ownerKey } ?: return false
        }
        media.forEach { it.bucketId = target.id; it.persist() }
        bumpMediaRevision(nodeId) // cr-0113: a re-bucket changes the listing's bucketId → advance the ETag so clients re-list
        return true
    }
}
