package com.veyq.app.features.media

import com.veyq.app.features.content.NodeEntity
import com.veyq.app.features.sharing.ShareIdentityEntity
import com.veyq.app.shared.blobstore.StoreObjectRef
import com.veyq.app.shared.blobstore.StoreReclaimStore
import com.veyq.app.shared.membership.MembershipEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
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
    /** How many times [wrappedMeta] was replaced — the version a meta write states (ADR 0077). */
    val metaVersion: Long = 0,
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

/** A node's uploader bucket for the "Beiträge" tab (en.memory.buckets, cr-0113): opaque owner_key + structure + count. */
data class BucketRow(
    val bucketId: String,
    val ownerKey: String,
    val parentBucketId: String?,
    val isPrimary: Boolean,
    val sealedName: String?,
    val mediaCount: Int,
    /** A contributor primary's share: its name is the identity's `share-identity-name` seal, bound to (scope,
     *  share, identity) (ADR 0072). Null for every other bucket. */
    val shareId: String? = null,
    /** An UPLOADER identity's primary: the identity's key wrapped to the Memory's drop key — its name is sealed
     *  under that key, not `K_mem` (ADR 0031 point 7). Null for every other bucket. */
    val wrappedIdentityKey: String? = null,
)

/**
 * Persistence for media blobs (cr-0028). Operator-blind: stores ciphertext wraps + opaque metadata only.
 * Who may act on a scope is decided before this store is reached, by the handler's `ScopeAccess` gate. The
 * manifest (per-chunk ciphertext sizes) is held as a small JSON array of integers — itself only ciphertext-size metadata.
 */
@ApplicationScoped
class MediaStore(private val reclaim: StoreReclaimStore) {

    /** The node, if it exists **live** in this scope (its `kind` decides the media-target rule). `deletedAt is
     *  null` (ADR 0061): this is the gate every media/bucket/cover handler routes through, so filtering it once
     *  takes a soft-deleted subtree out of the gallery, the bucket listings and the cover operations together —
     *  a poll on a trashed node answers 404 rather than a stale set. */
    fun findNode(scopeId: String, nodeId: String): NodeEntity? =
        NodeEntity.findById(nodeId)?.takeIf { it.scopeId == scopeId && it.deletedAt == null }

    /** Is the client-minted [blobId] already a blob, or promised to another open session — in any scope? */
    fun blobIdTaken(blobId: String): Boolean =
        BlobEntity.findById(blobId) != null || UploadSessionEntity.count("blobId", blobId) > 0

    /** Is the client-minted [mediaId] already a media, live or trashed, in any scope? */
    fun mediaIdTaken(mediaId: String): Boolean = MediaEntity.findById(mediaId) != null

    @Transactional
    fun createSession(scopeId: String, opener: UploadOpener, storageKey: String, d: UploadDeclare): String {
        val id = UUID.randomUUID().toString()
        val now = Instant.now()
        UploadSessionEntity().apply {
            this.id = id
            this.scopeId = scopeId
            openerUserId = (opener as? UploadOpener.Member)?.userId
            openerShareIdentityId = (opener as? UploadOpener.Contributor)?.shareIdentityId
            this.storageKey = storageKey
            chunkCount = d.chunkCount
            byteSize = d.byteSize
            wrappedBlobKey = d.wrappedBlobKey
            blobId = d.blobId
            ownerId = d.ownerId
            blobRole = d.role
            createdAt = now
            lastWindowAt = now
        }.persist()
        return id
    }

    /**
     * The open session [uploadId] in [scopeId] — **only for the [opener] who started it** (ADR 0013 §2). Every
     * operation that acts on a session (a window, a complete, a cover commit) resolves it here, so a second member
     * or a second identity on the same link cannot finish, extend or attach another's upload by knowing its id.
     * A session from before the opener was recorded carries none and matches nobody.
     */
    fun findSession(scopeId: String, uploadId: String, opener: UploadOpener): UploadSessionEntity? =
        UploadSessionEntity.findById(uploadId)?.takeIf { it.scopeId == scopeId && opener.opened(it) }

    /** Is session [uploadId] gone — spent by a complete that committed since it was read? A count, not [findSession]:
     *  outside a transaction the request's persistence context still holds the row it read, so a second `findById`
     *  would answer from that cache and never see the delete. */
    fun sessionSpent(uploadId: String): Boolean = UploadSessionEntity.count("id", uploadId) == 0L

    /** Mark session [uploadId] live now: a window was issued for it, which is the stale-session purge's clock. */
    @Transactional
    fun touchWindow(uploadId: String) {
        UploadSessionEntity.update("lastWindowAt = ?1 where id = ?2", Instant.now(), uploadId)
    }

    /**
     * Promote a media's verified sessions to blobs + attach to the node, consuming every session. The original
     * session becomes `veyq_media.blob_id`; each derivative becomes its own blob + a `veyq_media_derivative`
     * row. One transaction — all blobs, the media, and the derivative rows commit together. Returns
     * (originalBlobId, mediaId), or null — nothing written — when a session was already spent.
     *
     * The sessions are single-use, so they are **spent first** (developer-styleguide §12): each delete is the
     * transaction's first write and its row count decides. Two completes of one upload both found the session live;
     * the loser's delete waits for the winner's and then hits no row, so it answers `upload_not_found` exactly as a
     * replay does — instead of dying at the blob's primary key with a 500 that, in a batch, hid the items already
     * attached.
     */
    @Transactional
    fun attach(
        mediaId: String,
        original: UploadSessionEntity,
        nodeId: String,
        originalManifest: List<Long>?,
        derivatives: List<DerivativeAttach>,
        wrappedMeta: String?,
        wrappedExif: String?,
        shareIdentityId: String? = null, // cr-0101: the contributor identity, when attached via a CONTRIBUTOR share
        uploaderUserId: String? = null,  // cr-0113: the member uploader (member path); null on the contributor path
        targetBucketId: String? = null,  // cr-0113: an explicit target bucket; null = the uploader's primary
    ): Pair<String, String>? {
        val sessionIds = listOf(original.id) + derivatives.map { it.session.id }
        if (sessionIds.sumOf { UploadSessionEntity.delete("id = ?1", it) } != sessionIds.size.toLong()) {
            QuarkusTransaction.setRollbackOnly() // a session spent elsewhere: unspend the ones this call took
            return null
        }
        val scopeId = original.scopeId
        val ownerKey = ownerKeyFor(uploaderUserId, shareIdentityId)
        val bucket = resolveAttachBucket(scopeId, nodeId, ownerKey, targetBucketId)
        val originalBlobId = persistBlob(original, originalManifest)
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

    /** Persist a blob row from a verified session, under the client-minted id its wrap is bound to (ADR 0072).
     *  The handler checked [UploadSessionEntity.boundTo] first, so the id is present. */
    private fun persistBlob(session: UploadSessionEntity, manifest: List<Long>?): String {
        val blobId = checkNotNull(session.blobId) { "a session without a blob id is refused before it is attached" }
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
     * Hard-delete one media's rows in FK order (derivative → media → blob) and put their store objects on the
     * reclaim list — the single shared body behind the retention purge, the member-expedited purge and the
     * contributor delete-own. The caller owns the `@Transactional` boundary and any revision bump (only a
     * LIVE-media removal bumps a revision; a trashed row is already hidden from every list, so the two trash
     * purges don't).
     *
     * It **records** rather than returning keys (ADR 0062 Decision 1): the guarantee is "the same transaction as
     * the row delete", and the transaction is this method's caller's, not something a returned list can carry
     * across. `StoreReclaimStore.record` is `MANDATORY`, so a caller without one fails loudly here.
     */
    fun purgeMediaRows(media: MediaEntity) {
        val derivatives = MediaDerivativeEntity.find("mediaId = ?1", media.id).list()
        val blobIds = listOf(media.blobId) + derivatives.map { it.blobId }
        derivatives.forEach { MediaDerivativeEntity.deleteById(it.id) } // FK: the referencing rows go first
        MediaEntity.deleteById(media.id)
        blobIds.forEach { deleteBlobRow(it) }
    }

    /**
     * Hard-delete a single **trashed** media now (cr-0111, the member-expedited purge). Only a trashed media
     * (`deletedAt != null`) is purgeable — a live or unknown media returns false (⇒ a 404). No revision bump: a
     * trashed row is already excluded from every list. Its store objects go on the reclaim list in this tx.
     */
    @Transactional
    fun purgeMedia(scopeId: String, mediaId: String): Boolean {
        val media = MediaEntity.findById(mediaId)?.takeIf { it.scopeId == scopeId && it.deletedAt != null } ?: return false
        purgeMediaRows(media)
        return true
    }

    /**
     * Hard-delete every media trashed before [cutoff] — the retention purge (cr-0110, MediaTrashPurge), across
     * all scopes, putting their store objects on the reclaim list in the same tx. NO revision bump: trashed rows
     * are already excluded from every list, so no client ever saw them. Returns how many media were purged.
     */
    @Transactional
    fun purgeTrashedBefore(cutoff: Instant): Int {
        val expired = MediaEntity.find("deletedAt is not null and deletedAt < ?1", cutoff).list()
        expired.forEach { purgeMediaRows(it) }
        return expired.size
    }

    /**
     * Hard-delete every OPEN upload session whose last window was issued before [cutoff] (its `start` is its first
     * window), across all scopes, recording the objects
     * its client may already have staged in the store (uc.memory.upload E2 / en.memory.media E3). The row is the
     * **only** pointer to its `storage_key`, so dropping it without recording orphans those objects permanently
     * — and the record is written **in this transaction**, which is the guarantee the old post-commit delete
     * could not give. Two sources feed this: an upload cancelled or killed mid-flight, and a session the
     * `complete` budget gate rejected ([MediaHandler] reclaims that one's objects but leaves the row). The
     * reclaim row carries the full declared `chunk_count`; an abandoned session has an unknown number of those
     * actually present, so the store delete must tolerate absent keys — which it does, `deleteObject` being
     * idempotent. Returns how many sessions were purged.
     */
    @Transactional
    fun purgeSessionsIdleSince(cutoff: Instant): Int {
        val stale = UploadSessionEntity.find("lastWindowAt < ?1", cutoff).list()
        reclaim.record(stale.map { StoreObjectRef(it.storageKey, it.chunkCount) })
        stale.forEach { UploadSessionEntity.deleteById(it.id) }
        return stale.size
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
            m.metaVersion,
        )
    }

    /** The media, if it exists in this scope — carries the lazy `wrappedExif` for the lightbox exif endpoint. */
    fun findMedia(scopeId: String, mediaId: String): MediaEntity? =
        MediaEntity.findById(mediaId)?.takeIf { it.scopeId == scopeId }

    /** What [setMeta] did: wrote (the stored blob), found no such media, or refused a stale write (current value). */
    sealed interface SetMetaResult {
        data class Written(val wrappedMeta: String?) : SetMetaResult
        object Missing : SetMetaResult
        data class Stale(val wrappedMeta: String?, val metaVersion: Long) : SetMetaResult
    }

    /**
     * Overwrite a media's sealed `wrappedMeta` verbatim (opaque; e.g. a re-sealed corrected date) — only if
     * [expectedVersion] is still the stored `metaVersion` (ADR 0077). A miss writes nothing and moves no counter, so
     * no open gallery re-fetches a media that did not change.
     *
     * The compare-and-set is a bulk UPDATE that runs before this transaction loads the entity — the order
     * `NodesStore.applyUpdate` explains: the entity read afterwards holds the row as it now is.
     */
    @Transactional
    fun setMeta(scopeId: String, mediaId: String, wrappedMeta: String?, expectedVersion: Long): SetMetaResult {
        // cr-0107: the edit advances updatedAt (the keyset cursor), so the live poll re-fetches it. Panache binds no
        // null parameter, so a cleared tier (null) is spelled into the statement rather than bound.
        val guard = "metaVersion = ?1, updatedAt = ?2 where id = ?3 and scopeId = ?4 and metaVersion = ?5"
        val bound = arrayOf<Any>(expectedVersion + 1, Instant.now(), mediaId, scopeId, expectedVersion)
        val rows = if (wrappedMeta == null) {
            MediaEntity.update("wrappedMeta = null, $guard", *bound)
        } else {
            MediaEntity.update(
                "wrappedMeta = ?6, $guard",
                *bound, wrappedMeta,
            )
        }
        val media = MediaEntity.findById(mediaId)?.takeIf { it.scopeId == scopeId } ?: return SetMetaResult.Missing
        if (rows == 0) return SetMetaResult.Stale(media.wrappedMeta, media.metaVersion)
        bumpMediaRevision(media.nodeId) // a meta edit moves media_revision, not delete_revision
        return SetMetaResult.Written(media.wrappedMeta)
    }

    fun findBlob(scopeId: String, blobId: String): BlobEntity? =
        BlobEntity.findById(blobId)?.takeIf { it.scopeId == scopeId }

    /**
     * Commit a verified session as the node's **standalone cover** blob (cr-0073): persist one `veyq_blob`
     * (NO `veyq_media` row, so it never lists in the gallery), point the node's `cover_blob_id` at it, and
     * **atomically reclaim** any prior cover blob (its row is deleted and its store objects go on the reclaim
     * list, both in this transaction). Consumes the upload session. Returns the new ref, or null — nothing written —
     * when the session was already spent.
     *
     * The session is single-use, so it is **spent first**, as [attach] spends its own (developer-styleguide §12): two
     * commits of one upload both found it live, and the loser's delete waits for the winner's and then hits no row.
     * It answers what a replay answers — the upload is gone — instead of dying at the blob's primary key with a 500.
     */
    @Transactional
    fun commitCover(nodeId: String, session: UploadSessionEntity, manifest: List<Long>?): BlobRefData? {
        if (UploadSessionEntity.delete("id = ?1", session.id) != 1L) return null
        // No `deletedAt` predicate: the handler already resolved this node through the filtering [findNode]
        // gate before the verify step; this is the in-tx reload of that same row.
        val node = NodeEntity.findById(nodeId)!! // resolved by the handler before verify; reloaded in-tx
        deleteBlobRow(node.coverBlobId)
        val blobId = persistBlob(session, manifest)
        node.coverBlobId = blobId
        node.coverByteSize = session.byteSize
        node.persist()
        return BlobRefData(blobId, session.chunkCount, session.byteSize, session.wrappedBlobKey, manifest)
    }

    /**
     * Detach the node's cover blob (cr-0073): clear its `cover_blob_id`/`cover_byte_size`, delete the
     * `veyq_blob` row and put its store objects on the reclaim list — all in one transaction. A node (or scope)
     * with no cover blob records nothing and is idempotent; a null node likewise (the endpoint answers 204
     * either way).
     */
    @Transactional
    fun clearCover(scopeId: String, nodeId: String) {
        val node = NodeEntity.findById(nodeId)?.takeIf { it.scopeId == scopeId && it.deletedAt == null } ?: return
        deleteBlobRow(node.coverBlobId)
        node.coverBlobId = null
        node.coverByteSize = null
        node.persist()
    }

    /** Delete a blob row by id (if present) and put its store objects on the reclaim list, in the caller's
     *  transaction (cover + trash purge). The row is the only thing that knows the `storage_key`, so the record
     *  and the delete are one step, not two. */
    private fun deleteBlobRow(blobId: String?) {
        val blob = blobId?.let { BlobEntity.findById(it) } ?: return
        reclaim.record(blob.storageKey, blob.chunkCount)
        BlobEntity.deleteById(blob.id)
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

    /** The share identity a contributor primary belongs to — null for any other bucket. */
    private fun contributorIdentityOf(b: BucketEntity): ShareIdentityEntity? {
        if (!b.isPrimary) return null
        val owner = OwnerKey.parse(b.ownerKey) as? OwnerKey.Share ?: return null
        return ShareIdentityEntity.findById(owner.shareIdentityId)
    }

    /**
     * The wrapped identity key of every UPLOADER identity that owns one of [rows] (identity id → wrap): a row such a
     * guest added is sealed under the guest's own key, which only this wrap — opened through the Memory's drop key —
     * yields (ADR 0031 point 7). A CONTRIBUTOR identity has none: its rows are under `K_mem`.
     */
    fun uploaderKeys(rows: List<MediaRow>): Map<String, String> {
        val ids = rows.mapNotNull { r -> r.ownerKey?.let { (OwnerKey.parse(it) as? OwnerKey.Share)?.shareIdentityId } }.toSet()
        if (ids.isEmpty()) return emptyMap()
        return ShareIdentityEntity.find("id in ?1 and wrappedIdentityKey is not null", ids).list()
            .associate { it.id to it.wrappedIdentityKey!! }
    }

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
     * The node's buckets for the "Beiträge" tab (uc.memory.buckets): each bucket (primaries first) with its live media
     * count. Every medium already names its bucket — [attach], the one insert, resolves it, and no write clears it:
     * a bucket delete reparents its media first ([deleteBucketReparent]) or erases them with it (`NodeSubtreePurge`),
     * and media never change node. The lazy backfill this once ran for media from before buckets (V35) was deleted
     * before V1 (inbox #264), which has no such rows to carry.
     */
    @Transactional
    fun listBuckets(nodeId: String): List<BucketRow> {
        return BucketEntity.find("nodeId = ?1 order by isPrimary desc, createdAt", nodeId).list().map { b ->
            val count = MediaEntity.count("nodeId = ?1 and bucketId = ?2 and deletedAt is null", nodeId, b.id).toInt()
            val identity = contributorIdentityOf(b)
            BucketRow(
                b.id, b.ownerKey, b.parentBucketId, b.isPrimary, b.sealedName, count, identity?.shareId, identity?.wrappedIdentityKey,
            )
        }
    }

    /** Is the client-minted [bucketId] already a bucket, in any scope? */
    fun bucketIdTaken(bucketId: String): Boolean = BucketEntity.findById(bucketId) != null

    /** Create a sub-bucket one level under `ownerKey`'s primary (materialized if needed) — the caller's own or,
     *  post-cr0123, a contributor root a member curates. */
    @Transactional
    fun createSubBucket(bucketId: String, scopeId: String, nodeId: String, ownerKey: String, sealedName: String): BucketRow? {
        if (bucketIdTaken(bucketId)) return null // taken anywhere → nothing written (ADR 0072)
        val primary = ensurePrimaryBucket(scopeId, nodeId, ownerKey, primarySealedNameFor(ownerKey))
        val sub = BucketEntity().apply {
            id = bucketId
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
        // Locked first, as [moveMedia] locks its target: a move into this bucket finishes before the reparent reads
        // which media to carry, and one that comes after finds the bucket gone.
        val sub = BucketEntity.findById(bucketId, LockModeType.PESSIMISTIC_WRITE) ?: return
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
            // Under the bucket row's lock, which a sub-bucket delete takes first ([deleteBucketReparent]): read
            // without it, a move into a bucket being deleted wrote the gone bucket's id and failed its commit on the
            // foreign key — a 500 where a move into a missing bucket answers false.
            BucketEntity.findById(targetBucketId, LockModeType.PESSIMISTIC_WRITE)
                ?.takeIf { it.scopeId == scopeId && it.nodeId == nodeId && it.ownerKey == ownerKey } ?: return false
        }
        media.forEach { it.bucketId = target.id; it.persist() }
        bumpMediaRevision(nodeId) // cr-0113: a re-bucket changes the listing's bucketId → advance the ETag so clients re-list
        return true
    }
}
