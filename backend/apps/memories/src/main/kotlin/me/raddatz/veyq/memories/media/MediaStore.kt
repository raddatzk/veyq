package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.backup.ErasureLedger
import me.raddatz.veyq.core.moderation.block.BlockKind
import me.raddatz.veyq.core.moderation.block.MeasureTargets
import me.raddatz.veyq.core.moderation.guestwrite.GuestWriteRecords
import me.raddatz.veyq.core.identity.PrincipalKey
import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.blob.BlobUploadStore
import me.raddatz.veyq.core.blob.SharedBlobSpec
import me.raddatz.veyq.core.blob.SharedBlobs
import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.content.NodesStore
import me.raddatz.veyq.core.items.ItemsCursor
import me.raddatz.veyq.core.items.ItemsPage
import me.raddatz.veyq.core.items.NodeItemsRevisions
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.sharing.ShareIdentityEntity
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
    /** When this media last changed (cr-0107): the attach time at attach, `now` on a meta edit or restore — the keyset cursor sort key. */
    val updatedAt: Instant,
    /** When this media was trashed (cr-0110), or null for a live media; populated only in the trashed listing. */
    val deletedAt: Instant? = null,
    /** Who trashed this media (cr-0110, the audit), or null for a live media; populated only in the trashed listing. */
    val deletedBy: String? = null,
    /** cr-0113: the uploader's opaque owner_key ("user:{id}"|"share:{id}"), or null for a pre-buckets member row. */
    val ownerKey: String? = null,
    /** cr-0113: the bucket this media lives in, or null = the uploader's primary. */
    val bucketId: String? = null,
    /** How many times [wrappedMeta] was replaced — the revision a meta write states (ADR 0077). */
    val metaRevision: Long = 0,
    /** The original blob's sealed metadata as its upload declared it (core's `blob-meta`; opaque, write-once). */
    val wrappedBlobMeta: String?,
)

/**
 * One medium a share link's guest is served (`listShareMedia`): the read-back [row], the subtree node it hangs under
 * (its content key opens it) and the guest identity that added it — null for a member's. Whether the identity is
 * SERVED is the share read's rule, not this row's (ADR 0030 point 8).
 */
data class ShareMediaRow(val nodeId: String, val identityId: String?, val row: MediaRow)

/** One role-tagged derivative tier of a media (its own blob metadata + wrap). */
data class DerivativeRow(
    val kind: String,
    val blobId: String,
    val chunkCount: Int,
    val byteSize: Long,
    val wrappedBlobKey: String,
    val manifest: List<Long>?,
    /** The derivative blob's sealed metadata as its upload declared it (opaque, write-once). */
    val wrappedBlobMeta: String?,
)

/** A verified derivative upload to attach: its role + session + the verified per-chunk manifest. */
data class DerivativeAttach(val kind: String, val session: UploadSessionEntity, val manifest: List<Long>?)

/** Where a medium's original comes from: its verified upload, or a new row on an object the node already stores. */
sealed interface OriginalAttach {
    data class Upload(val session: UploadSessionEntity, val manifest: List<Long>?) : OriginalAttach

    /** ADR 0013 § 5: the picked file was found equal to the node's video banner; [scopeId] is the completion's. */
    data class Shared(val scopeId: String, val spec: SharedBlobSpec) : OriginalAttach
}

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
    /** A WRITE_ONLY identity's primary: the identity's key wrapped to the Memory's drop key — its name is sealed
     *  under that key, not `K_node` (ADR 0031 point 7). Null for every other bucket. */
    val wrappedIdentityKey: String? = null,
)

/**
 * Persistence for the Memories media (cr-0028): media, derivative and bucket rows and the gallery
 * revision counters, on top of core's [BlobUploadStore] (sessions and blob rows). A node's cover is core's
 * (`NodeCoverStore`, inbox #411). Operator-blind: stores ciphertext wraps + opaque metadata only.
 * Who may act on a scope is decided before this store is reached, by the handler's `ScopeAccess` gate. The
 * manifest (per-chunk ciphertext sizes) is held as a small JSON array of integers — itself only ciphertext-size metadata.
 */
@ApplicationScoped
class MediaStore(
    private val blobs: BlobUploadStore,
    private val shared: SharedBlobs,
    private val nodes: NodesStore,
    private val items: NodeItemsRevisions,
    /** Core's guest-write record: a medium's rows go with the medium (ADR 0093 § 10), from [purgeMediaRows]. */
    private val guestWriteRecords: GuestWriteRecords,
    /** Core's erasure ledger: a hard-deleted medium enters it as Memories' `MEDIA` (ADR 0056 decision 8, [MemoriesErasures]). */
    private val ledger: ErasureLedger,
    /** Core's measures: a content block on a medium ends with it (ADR 0093 § 6), from [purgeMediaRows]. */
    private val measures: MeasureTargets,
) {

    /** The node, if it exists **live** in this scope (its `kind` decides the media-target rule). `deletedAt is
     *  null` (ADR 0061): this is the gate every media/bucket/cover handler routes through, so filtering it once
     *  takes a soft-deleted subtree out of the gallery, the bucket listings and the cover operations together —
     *  a poll on a trashed node answers 404 rather than a stale set. */
    fun findNode(scopeId: String, nodeId: String): NodeEntity? =
        NodeEntity.findById(nodeId)?.takeIf { it.scopeId == scopeId && it.deletedAt == null }

    /** Is the client-minted [mediaId] already a media, live or trashed, in any scope? */
    fun mediaIdTaken(mediaId: String): Boolean = MediaEntity.findById(mediaId) != null

    /**
     * Promote a media's verified sessions to blobs + attach to the node, consuming every session. The original
     * session becomes `veyq_media.blob_id`; each derivative becomes its own blob + a `veyq_media_derivative`
     * row. One transaction — all blobs, the media, and the derivative rows commit together. Returns
     * (originalBlobId, mediaId), or null — nothing written — when a session was already spent, or [admitted] refuses;
     * (null, mediaId) — nothing written — when a shared original's source does not pass [SharedBlobs.persist].
     * A shared original ([OriginalAttach.Shared], ADR 0013 § 5) is a new row on the stored object of the node's video
     * banner: nothing of it was uploaded, its derivatives (the posters) were, as always.
     * [admitted] is the caller's gate asked again, before anything else in this transaction — a guest's link under
     * the row lock its delete holds (`ShareMediaHandler.complete`); a member's complete passes none.
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
        original: OriginalAttach,
        nodeId: String,
        derivatives: List<DerivativeAttach>,
        wrappedMeta: String?,
        wrappedExif: String?,
        shareIdentityId: String? = null, // cr-0101: the contributor identity, when attached via a READ_WRITE share
        uploaderUserId: String? = null,  // cr-0113: the member uploader (member path); null on the contributor path
        admitted: () -> Boolean = { true },
        /** Told the medium's id last, once it is attached (the guest-write record, ADR 0093 § 10); never on a refusal. */
        attached: (mediaId: String) -> Unit = {},
    ): Pair<String?, String>? {
        if (!admitted()) return null
        val scopeId = when (original) {
            is OriginalAttach.Upload -> original.session.scopeId
            is OriginalAttach.Shared -> original.scopeId
        }
        // A shared original's source is checked under the scope row's lock, the order every attachment writer takes
        // (scope, then the sessions it spends), so a removal of the banner it shares is seen or waits (`SharedBlobs`).
        if (original is OriginalAttach.Shared) nodes.lockScope(scopeId)
        val sessionIds = listOfNotNull((original as? OriginalAttach.Upload)?.session?.id) + derivatives.map { it.session.id }
        if (sessionIds.sumOf { UploadSessionEntity.delete("id = ?1", it) } != sessionIds.size.toLong()) {
            QuarkusTransaction.setRollbackOnly() // a session spent elsewhere: unspend the ones this call took
            return null
        }
        val originalBlobId = when (original) {
            is OriginalAttach.Upload -> blobs.persistBlob(original.session, original.manifest)
            is OriginalAttach.Shared -> shared.persist(scopeId, nodeId, original.spec) ?: run {
                QuarkusTransaction.setRollbackOnly() // the derivatives' sessions stay open for a retry as an upload
                return null to mediaId
            }
        }
        val ownerKey = ownerKeyFor(uploaderUserId, shareIdentityId)
        val bucket = ensurePrimaryBucket(scopeId, nodeId, ownerKey, primarySealedNameFor(ownerKey))
        val now = Instant.now()
        val medium = MediaEntity().apply {
            id = mediaId
            this.scopeId = scopeId
            this.nodeId = nodeId
            this.blobId = originalBlobId
            this.wrappedMeta = wrappedMeta
            // ADR 0093 § 8: the uploader — the upload's authenticated opener — wrote the meta it carries.
            val uploader = PrincipalKey.of(uploaderUserId, shareIdentityId)?.takeIf { wrappedMeta != null }
            metaEditorUserId = uploader?.userId
            metaEditorIdentityId = uploader?.identityId
            this.wrappedExif = wrappedExif
            this.shareIdentityId = shareIdentityId
            this.createdBy = ownerKey // cr-0113: the buckets attribution (denormalized owner_key)
            this.bucketId = bucket.id // cr-0113: the uploader's primary; a move files it elsewhere
            updatedAt = now // cr-0107: the attach time (the served `updatedAt`); the list order is `position`, below
        }.also { it.persist() }
        for (d in derivatives) {
            val derivBlobId = blobs.persistBlob(d.session, d.manifest)
            MediaDerivativeEntity().apply {
                id = UUID.randomUUID().toString()
                this.mediaId = mediaId
                kind = d.kind
                blobId = derivBlobId
            }.persist()
        }
        // cr-0107: an add moves the node's item revision (the live-updates change signal), not its delete revision — and
        // the revision it moved to is the new medium's place at the list's end (ADR 0033 decision 3, [MediaEntity.position]).
        medium.position = items.bump(scopeId, nodeId)
        attached(mediaId)
        return originalBlobId to mediaId
    }

    /**
     * One page of the node's live media as a **keyset delta** past [since] (cr-0107, ADR 0033 decisions 3 and 6): at most
     * [limit] rows whose position ([MediaEntity.position]) is past the cursor's (id as the tie-break), in the order their
     * changes committed, so the client's next page — or next poll — continues from the last row and nothing lands behind
     * it. [since] = null ⇒ from the beginning (a first load).
     */
    fun listNodeMediaSince(scopeId: String, nodeId: String, since: ItemsCursor?, limit: Int): ItemsPage<MediaRow> =
        keysetPage(scopeId, nodeId, live = true, since, limit)

    /**
     * One keyset page of the node's live ([live]) or trashed media: `limit + 1` rows read in cursor order (the extra one
     * only says more remain), mapped with a constant number of queries ([toRows]).
     */
    private fun keysetPage(scopeId: String, nodeId: String, live: Boolean, since: ItemsCursor?, limit: Int): ItemsPage<MediaRow> {
        val state = if (live) "deletedAt is null" else "deletedAt is not null"
        val query = if (since == null) {
            MediaEntity.find("scopeId = ?1 and nodeId = ?2 and $state order by position, id", scopeId, nodeId)
        } else {
            MediaEntity.find(
                "scopeId = ?1 and nodeId = ?2 and $state and (position > ?3 or (position = ?3 and id > ?4)) order by position, id",
                scopeId, nodeId, since.position.toInt(), since.id,
            )
        }
        val page = ItemsPage.of(query.range(0, limit).list(), limit) // range is inclusive: limit + 1 rows
        // The cursor is the last row READ, not the last mapped: a row dropped for a missing blob is still passed.
        return ItemsPage(toRows(page.items), page.more, page.items.lastOrNull()?.let { ItemsCursor(it.position.toLong(), it.id) })
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
     * (the audit) and bump BOTH counters (core's `NodeItemsRevisions.bump(deleted = true)`) so an open gallery drops the tile
     * via the id-set reconcile (cr-0107). Idempotent — an already-trashed media is a no-op (no re-bump). Returns
     * the media's nodeId, or null when no such media exists in the scope (⇒ a non-enumerable 404).
     */
    @Transactional
    fun trashMedia(scopeId: String, mediaId: String, userId: String): String? {
        // The read is the row's lock, as every delete of the row takes it (`purgeMedia`, a guest's
        // `MediaShareGuestWrites.deleteOwn`): one that holds it finishes first, and this then finds nothing — the 404 a replay
        // gets. Read plainly, the trash flushed onto a row the delete had removed and answered 500 (inbox #313).
        val media = MediaEntity.findById(mediaId, LockModeType.PESSIMISTIC_WRITE)?.takeIf { it.scopeId == scopeId } ?: return null
        if (media.deletedAt != null) return media.nodeId // already trashed — idempotent
        media.deletedAt = Instant.now()
        media.deletedBy = userId
        media.persist()
        items.bump(scopeId, media.nodeId, deleted = true)
        return media.nodeId
    }

    /**
     * Restore a trashed media (cr-0110): clear `deleted_at`/`deleted_by`, advance `updated_at` so it re-surfaces
     * in the live keyset delta, and bump the item revision (an add-back, not a delete). Idempotent on a live media.
     * Returns the nodeId, or null when no such media in the scope (e.g. already purged) ⇒ a 404.
     */
    @Transactional
    fun restoreMedia(scopeId: String, mediaId: String): String? {
        // A guarded update, not a read and a managed write: a purge holds the row while it erases it, and this waits
        // and then matches nothing — the 404 a replay gets — where a flush of a row read before the erase was a 500
        // (developer-styleguide §12). `updatedAt` re-surfaces it in the live keyset delta (cr-0107).
        val restored = MediaEntity.update(
            "deletedAt = null, deletedBy = null, updatedAt = ?1 where id = ?2 and scopeId = ?3 and deletedAt is not null",
            Instant.now(), mediaId, scopeId,
        ) == 1
        val media = MediaEntity.findById(mediaId)?.takeIf { it.scopeId == scopeId } ?: return null
        if (restored) placeAt(mediaId, items.bump(scopeId, media.nodeId)) // back at the list's end: the bump's revision
        return media.nodeId // restored now, or already live — idempotent
    }

    /**
     * One page of the node's TRASHED media (cr-0110) — the Trash tab list (rows carry deletedAt/deletedBy) — on the live
     * list's keyset, so one cursor and one limit serve both states of `listNodeMedia`. The client orders the Trash tab
     * by `deletedAt` once it holds every page; a trash during the paging is in the next open of the tab (not live).
     */
    fun listNodeTrashed(scopeId: String, nodeId: String, since: ItemsCursor?, limit: Int): ItemsPage<MediaRow> =
        keysetPage(scopeId, nodeId, live = false, since, limit)

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
        // Its objects, read while its rows still name them: one another row of the node keeps (a cover or a banner sharing
        // it, ADR 0013 § 5) is what a measure on the medium hangs on once the medium is gone (below).
        val keys = blobIds.mapNotNull { BlobEntity.findById(it)?.storageKey }.toSet()
        derivatives.forEach { MediaDerivativeEntity.deleteById(it.id) } // FK: the referencing rows go first
        MediaEntity.deleteById(media.id)
        blobIds.forEach { blobs.deleteBlobRow(it) }
        val surviving = keys.filter { BlobEntity.count("storageKey", it) > 0 }.toSet()
        guestWriteRecords.forget(listOf(media.id)) // a guest's upload record names the medium: it goes with it
        // The erasure ledger's line (ADR 0056 decision 8): a guest's delete of its own upload leaves no trash to re-fire,
        // and a capture older than any of these brings the medium back.
        ledger.erasedByApplication(MemoriesErasures.Kind.MEDIA.name, media.id, media.scopeId)
        // A content block on the medium ends with it (ADR 0093 § 6, GDPR Art. 5(1)(e)) — it would otherwise stand on nothing
        // and hold its case's records forever. After the row's delete, which it flushes (MeasureTargets.erased).
        // ... unless another row still holds its object: then the measure hangs on that object (owner 2026-10-19).
        measures.erased(
            listOf(BlockKind.CONTENT_ITEM), listOf(media.id),
            holding = if (surviving.isEmpty()) emptyMap() else mapOf(media.id to surviving),
        )
    }

    /**
     * Hard-delete a single **trashed** media now (cr-0111, the member-expedited purge). Only a trashed media
     * (`deletedAt != null`) is purgeable — a live or unknown media returns false (⇒ a 404). No revision bump: a
     * trashed row is already excluded from every list. Its store objects go on the reclaim list in this tx.
     */
    @Transactional
    fun purgeMedia(scopeId: String, mediaId: String): Boolean {
        // The read is the row's lock, so a restore that committed first is seen live, and one that comes after waits
        // and finds nothing to restore. Read plainly, a photo restored in between was erased anyway.
        val media = MediaEntity.findById(mediaId, LockModeType.PESSIMISTIC_WRITE)
            ?.takeIf { it.scopeId == scopeId && it.deletedAt != null } ?: return false
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
        // The selection is the lock, and the predicate is re-checked on what it locked: a restore that committed while
        // this waited is live now and stays. Selected by a plain read, a photo restored in between was erased anyway.
        val expired = MediaEntity.find("deletedAt is not null and deletedAt < ?1", cutoff)
            .withLock(LockModeType.PESSIMISTIC_WRITE).list()
            .filter { it.deletedAt?.isBefore(cutoff) == true }
        expired.forEach { purgeMediaRows(it) }
        return expired.size
    }

    /**
     * Map a page of media entities to their read-back rows, in their order: the derivatives of the whole page in one
     * query, and every blob they and the originals name in one more — a constant number of queries per page, whatever
     * its size (cr-gallery-list-paging; one query per photo and one per derivative cost 40,017 SELECTs for 10,000
     * photos, `MediaListQueryCountSliceTest`). A medium whose original blob is missing is dropped, a derivative whose
     * blob is missing left out, as the per-row mapping did.
     */
    private fun toRows(media: List<MediaEntity>): List<MediaRow> {
        if (media.isEmpty()) return emptyList()
        val derivatives = MediaDerivativeEntity.list("mediaId in ?1", media.map { it.id }).groupBy { it.mediaId }
        val blobIds = media.map { it.blobId } + derivatives.values.flatten().map { it.blobId }
        val blobRows = BlobEntity.list("id in ?1", blobIds.distinct()).associateBy { it.id }
        return media.mapNotNull { m ->
            val blob = blobRows[m.blobId] ?: return@mapNotNull null
            val tiers = derivatives[m.id].orEmpty().mapNotNull { d ->
                val derivBlob = blobRows[d.blobId] ?: return@mapNotNull null
                DerivativeRow(d.kind, derivBlob.id, derivBlob.chunkCount, derivBlob.byteSize, derivBlob.wrappedBlobKey, blobs.decodeManifest(derivBlob.manifest), derivBlob.wrappedBlobMeta)
            }
            MediaRow(
                m.id, blob.id, blob.chunkCount, blob.byteSize, blob.wrappedBlobKey, blobs.decodeManifest(blob.manifest),
                tiers, m.wrappedMeta, m.updatedAt,
                m.deletedAt, m.deletedBy, // cr-0110: null for live media, populated in the trashed listing
                m.createdBy, m.bucketId, // cr-0113: `attach` stamps both
                m.metaRevision,
                wrappedBlobMeta = blob.wrappedBlobMeta,
            )
        }
    }

    /** The media, if it exists in this scope — carries the lazy `wrappedExif` for the lightbox exif endpoint. */
    fun findMedia(scopeId: String, mediaId: String): MediaEntity? =
        MediaEntity.findById(mediaId)?.takeIf { it.scopeId == scopeId }

    /** What [setMeta] did: wrote (the stored blob), found no such media, or refused a stale write (current value). */
    sealed interface SetMetaResult {
        data class Written(val wrappedMeta: String?) : SetMetaResult
        object Missing : SetMetaResult
        data class Stale(val wrappedMeta: String?, val metaRevision: Long) : SetMetaResult
    }

    /**
     * Overwrite a media's sealed `wrappedMeta` verbatim (opaque; e.g. a re-sealed corrected date) — only if
     * [expectedRevision] is still the stored `metaRevision` (ADR 0077). A miss writes nothing and moves no counter, so
     * no open gallery re-fetches a media that did not change.
     *
     * The compare-and-set is a bulk UPDATE that runs before this transaction loads the entity — the order
     * `NodesStore.applyUpdate` explains: the entity read afterwards holds the row as it now is.
     */
    @Transactional
    fun setMeta(scopeId: String, mediaId: String, wrappedMeta: String?, expectedRevision: Long, editor: PrincipalKey.User): SetMetaResult {
        // cr-0107: the edit advances updatedAt (the keyset cursor), so the live poll re-fetches it. Panache binds no
        // null parameter, so a cleared tier (null) is spelled into the statement rather than bound — and so is its
        // editor (ADR 0093 § 8): a cleared meta has none, a written one the member who wrote it.
        val guard = "metaRevision = ?1, updatedAt = ?2 where id = ?3 and scopeId = ?4 and metaRevision = ?5"
        val bound = arrayOf<Any>(expectedRevision + 1, Instant.now(), mediaId, scopeId, expectedRevision)
        val rows = if (wrappedMeta == null) {
            MediaEntity.update("wrappedMeta = null, metaEditorUserId = null, metaEditorIdentityId = null, $guard", *bound)
        } else {
            MediaEntity.update(
                "wrappedMeta = ?6, metaEditorUserId = ?7, metaEditorIdentityId = null, $guard",
                *bound, wrappedMeta, editor.userId,
            )
        }
        val media = MediaEntity.findById(mediaId)?.takeIf { it.scopeId == scopeId } ?: return SetMetaResult.Missing
        if (rows == 0) return SetMetaResult.Stale(media.wrappedMeta, media.metaRevision)
        placeAt(mediaId, items.bump(scopeId, media.nodeId)) // a meta edit moves the item revision, not the delete revision
        return SetMetaResult.Written(media.wrappedMeta)
    }

    /**
     * Put [mediaId] at its list's end: [position] is the revision the writer's `items.bump` just wrote, stamped in the same
     * transaction while the bump's row lock is held — so a client that already passed that place cannot see the medium
     * land behind it (ADR 0033 decision 3). For a writer whose row this transaction already changed (and locked) by a
     * guarded UPDATE; an attach stamps its new entity itself.
     */
    private fun placeAt(mediaId: String, position: Int) {
        MediaEntity.update("position = ?1 where id = ?2", position, mediaId)
    }

    // --- buckets (en.memory.buckets, ADR 0034, cr-0113) ----------------------

    /** The owner_key for a NEW upload: a contributor `share:{id}`, else a member `user:{id}`. */
    private fun ownerKeyFor(uploaderUserId: String?, shareIdentityId: String?): String =
        when {
            shareIdentityId != null -> PrincipalKey.Share(shareIdentityId)
            uploaderUserId != null -> PrincipalKey.User(uploaderUserId)
            else -> PrincipalKey.User("unknown")
        }.format()

    /** The share identity a contributor primary belongs to — null for any other bucket. */
    private fun contributorIdentityOf(b: BucketEntity): ShareIdentityEntity? {
        if (!b.isPrimary) return null
        val owner = PrincipalKey.parse(b.ownerKey) as? PrincipalKey.Share ?: return null
        return ShareIdentityEntity.findById(owner.identityId)
    }

    /**
     * The wrapped identity key of every WRITE_ONLY identity that owns one of [rows] (identity id → wrap): a row such a
     * guest added is sealed under the guest's own key, which only this wrap — opened through the Memory's drop key —
     * yields (ADR 0031 point 7). A READ_WRITE identity has none: its rows are under `K_node`.
     */
    fun uploaderKeys(rows: List<MediaRow>): Map<String, String> =
        uploaderKeysOf(rows.mapNotNull { r -> r.ownerKey?.let { (PrincipalKey.parse(it) as? PrincipalKey.Share)?.identityId } }.toSet())

    /**
     * The same, by guest identity id: every identity of [identityIds] — of ANY link of the Memory — that holds a
     * wrapped identity key, i.e. a WRITE_ONLY one (identity id → wrap). Never a READ_WRITE identity, whatever link it
     * came through, and never a name.
     */
    fun uploaderKeysOf(identityIds: Set<String>): Map<String, String> {
        if (identityIds.isEmpty()) return emptyMap()
        return ShareIdentityEntity.find("id in ?1 and wrappedIdentityKey is not null", identityIds).list()
            .associate { it.id to it.wrappedIdentityKey!! }
    }

    /**
     * One page of the live media under [nodeIds] — a share's live subtree, or the one node of it a guest has open (core's
     * view gate already filtered them by its own `deletedAt is null`) — what `listShareMedia` serves a guest: at most
     * [limit] media past [after] in **id** order. Not the member list's `(updated_at, id)`: a guest is told no time of
     * the Memory's activity (ADR 0081 decision 4), and the id is the one key it already holds.
     */
    fun listShareMedia(scopeId: String, nodeIds: List<String>, after: String?, limit: Int): ItemsPage<ShareMediaRow> {
        if (nodeIds.isEmpty()) return ItemsPage(emptyList(), false) // the shared root itself is trashed: nothing to list
        // `deletedAt is null` — a trashed medium (uc.memory.delete-media, cr-0110) is a soft delete, so without this
        // predicate the guest listing keeps serving it for the whole retention window. Its sibling,
        // `MediaSharedSubtreeContributor.authorizedBlobIds`, authorizes the blob GET and carries the predicate
        // SEPARATELY on purpose: one covering for the other is how a later change reopens the hole silently
        // (`ShareTrashedMediaSliceTest` bites each on its own).
        val query = if (after == null) {
            MediaEntity.find("scopeId = ?1 and nodeId in ?2 and deletedAt is null order by id", scopeId, nodeIds)
        } else {
            MediaEntity.find("scopeId = ?1 and nodeId in ?2 and deletedAt is null and id > ?3 order by id", scopeId, nodeIds, after)
        }
        val page = ItemsPage.of(query.range(0, limit).list(), limit) // range is inclusive: limit + 1 rows
        val byId = page.items.associateBy { it.id }
        return ItemsPage(toRows(page.items).map { r -> byId.getValue(r.mediaId).let { m -> ShareMediaRow(m.nodeId, m.shareIdentityId, r) } }, page.more)
    }

    /** A primary's sealed name: for a contributor (`share:{id}`) copy the identity's sealed name; else null (member → username). */
    private fun primarySealedNameFor(ownerKey: String): String? =
        when (val owner = PrincipalKey.parse(ownerKey)) {
            is PrincipalKey.Share -> ShareIdentityEntity.findById(owner.identityId)?.sealedDisplayName
            is PrincipalKey.User -> null
        }

    /**
     * Find-or-create the uploader's PRIMARY bucket in a node (lazy, virtual-until-first-use). Refreshes a still-missing
     * sealed name (a contributor primary materialized before its identity name).
     *
     * One primary per `(node, owner_key)` is held by the node row's lock, taken first (ADR 0034 §4, developer-
     * styleguide §12: the parent's row lock): a second resolve of a missing primary waits for the first and then finds
     * it. Read without it, two first uploads of one uploader both found none and both created one
     * (`MediaCreateRaceSliceTest.concurrentFirstUploadsOfOneUploaderShareOnePrimary`). The lock is only taken —
     * nothing here reads the node, so a copy this transaction already holds does not matter. A caller that also locks
     * a bucket or media rows takes those first (bucket → media → node: [deleteBucketReparent], [moveMedia]), the order
     * a trash or a guest's delete (media → node) agrees with; `MediaLockOrderRaceSliceTest` deadlocks on either
     * writer's pair swapped (inbox #319).
     */
    private fun ensurePrimaryBucket(scopeId: String, nodeId: String, ownerKey: String, sealedName: String?): BucketEntity {
        NodeEntity.find("id", nodeId).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult()
        val existing = BucketEntity.find("nodeId = ?1 and ownerKey = ?2 and isPrimary = true", nodeId, ownerKey).firstResult()
        // A primary's name is a copy of its contributor identity's display name (primarySealedNameFor), so that identity is
        // its editor (ADR 0093 § 8) whoever's write materializes it; a member's primary has no name and no editor.
        val editor = (PrincipalKey.parse(ownerKey) as? PrincipalKey.Share)?.takeIf { sealedName != null }
        if (existing != null) {
            if (existing.sealedName == null && sealedName != null) {
                existing.sealedName = sealedName
                existing.nameWrittenBy(editor)
                existing.persist()
            }
            return existing
        }
        return BucketEntity().apply {
            id = UUID.randomUUID().toString()
            this.scopeId = scopeId
            this.nodeId = nodeId
            this.ownerKey = ownerKey
            parentBucketId = null
            this.sealedName = sealedName
            nameWrittenBy(editor)
            isPrimary = true
            createdAt = Instant.now()
        }.also { it.persist() }
    }


    /**
     * The node's buckets for the "Beiträge" tab (uc.memory.buckets): each bucket (primaries first) with its live media
     * count. Every medium already names its bucket — [attach], the one insert, resolves it, and no write clears it:
     * a bucket delete reparents its media first ([deleteBucketReparent]) or erases them with it (`NodeSubtreePurge`),
     * and media never change node. The lazy backfill this once ran for media from before buckets was deleted
     * before V1 (inbox #264), which has no such rows to carry.
     */
    @Transactional
    fun listBuckets(nodeId: String): List<BucketRow> {
        return BucketEntity.find("nodeId = ?1 order by isPrimary desc, createdAt, id", nodeId).list().map { b ->
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
    fun createSubBucket(
        bucketId: String, scopeId: String, nodeId: String, ownerKey: String, sealedName: String, editor: PrincipalKey.User,
    ): BucketRow? {
        if (bucketIdTaken(bucketId)) return null // taken anywhere → nothing written (ADR 0072)
        val primary = ensurePrimaryBucket(scopeId, nodeId, ownerKey, primarySealedNameFor(ownerKey))
        val sub = BucketEntity().apply {
            id = bucketId
            this.scopeId = scopeId
            this.nodeId = nodeId
            this.ownerKey = ownerKey
            parentBucketId = primary.id
            this.sealedName = sealedName
            nameWrittenBy(editor) // the member who named it, also under a contributor's root (ADR 0093 § 8)
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
    fun renameBucket(bucketId: String, sealedName: String, editor: PrincipalKey.User): BucketRow? {
        val b = BucketEntity.findById(bucketId) ?: return null
        b.sealedName = sealedName
        b.nameWrittenBy(editor)
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
        // Its media next, before the primary's node lock: bucket → media → node is the order [moveMedia] takes too,
        // and a trash or a guest's delete of one of them holds media → node — taken the other way round, two of these
        // could each hold what the other waits for.
        MediaEntity.find("bucketId = ?1 order by id", bucketId).withLock(LockModeType.PESSIMISTIC_WRITE).list()
        val primary = ensurePrimaryBucket(sub.scopeId, sub.nodeId, sub.ownerKey, primarySealedNameFor(sub.ownerKey))
        MediaEntity.update("bucketId = ?1 where bucketId = ?2", primary.id, bucketId)
        items.bump(sub.scopeId, sub.nodeId) // cr-0113: reparent changed the media's bucketId → advance the ETag
        BucketEntity.deleteById(bucketId)
    }

    /**
     * Move media into a target bucket WITHIN one owner-root (null = that owner's primary). The owner is **derived
     * from the source media** — they must all share one `owner_key` (else a cross-owner move ⇒ false/403) — and the
     * target bucket must have that same owner. Every medium is in this node + live. Returns false (⇒ 403) on any
     * mismatch. The caller-is-member gate lives in the handler (cr-0123).
     *
     * Locks in the order a sub-bucket delete takes them ([deleteBucketReparent]): the target bucket, then each medium
     * (sorted, so two moves cannot cross), then the node (the primary's resolve, the revision bump). The target
     * bucket first: read without its lock, a move into a bucket being deleted wrote the gone bucket's id and failed
     * its commit on the foreign key — a 500 where a move into a missing bucket answers false. Each medium under its
     * lock, as every delete of it takes it: read plainly, a move of a guest's upload that the guest deleted in between
     * flushed onto a gone row, or answered success for it (inbox #313); now it finds nothing and answers false.
     */
    @Transactional
    fun moveMedia(scopeId: String, nodeId: String, mediaIds: List<String>, targetBucketId: String?): Boolean {
        val explicit = targetBucketId?.let { id ->
            BucketEntity.findById(id, LockModeType.PESSIMISTIC_WRITE)?.takeIf { it.scopeId == scopeId && it.nodeId == nodeId } ?: return false
        }
        val media = mediaIds.distinct().sorted().map { id ->
            MediaEntity.findById(id, LockModeType.PESSIMISTIC_WRITE)
                ?.takeIf { it.scopeId == scopeId && it.nodeId == nodeId && it.deletedAt == null } ?: return false
        }
        // One shared root only — a single distinct source owner_key; more than one would be a cross-owner move.
        // `created_by` is the owner: `attach`, the only media insert, stamps it. The fallbacks this once had for
        // media from before buckets (the guest identity, then the scope's first member) had no input left, and the
        // second would have attributed another member's photo to the scope owner and let a cross-owner move through
        // (inbox #305). A medium without it is a bug, and must fail like one.
        val ownerKey = media.map { checkNotNull(it.createdBy) { "media ${it.id} has no created_by" } }.distinct().singleOrNull()
            ?: return false
        val target = when {
            explicit == null -> ensurePrimaryBucket(scopeId, nodeId, ownerKey, primarySealedNameFor(ownerKey))
            explicit.ownerKey == ownerKey -> explicit
            else -> return false
        }
        media.forEach { it.bucketId = target.id; it.persist() }
        items.bump(scopeId, nodeId) // cr-0113: a re-bucket changes the listing's bucketId → advance the ETag so clients re-list
        return true
    }
}
