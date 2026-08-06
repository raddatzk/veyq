package com.veyq.app.features.media

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * An OPEN upload session (cr-0028): opened by `start` (which presigns a PUT per chunk under `storageKey`),
 * consumed by `complete` (stat + attach). Operator-blind: `wrappedBlobKey` is opaque ciphertext; `storageKey`
 * is a random object-key prefix. Abandoned sessions are reclaimed by GC (a later slice).
 */
@Entity
@Table(name = "veyq_upload_session")
class UploadSessionEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", length = 36)
    lateinit var id: String

    @Column(name = "scope_id", nullable = false, length = 36)
    lateinit var scopeId: String

    @Column(name = "storage_key", nullable = false, length = 64)
    lateinit var storageKey: String

    @Column(name = "chunk_count", nullable = false)
    var chunkCount: Int = 0

    @Column(name = "byte_size", nullable = false)
    var byteSize: Long = 0

    @Column(name = "wrapped_blob_key", nullable = false)
    lateinit var wrappedBlobKey: String

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    companion object : PanacheCompanionBase<UploadSessionEntity, String>
}

/**
 * A completed blob (cr-0028): the chunk `manifest` (per-chunk ciphertext sizes, JSON array, or null when the
 * dev store has no transport) + the `K_blob` wrap. The bytes live in the object store under `storageKey/<i>`,
 * never here. Operator-blind throughout.
 */
@Entity
@Table(name = "veyq_blob")
class BlobEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", length = 36)
    lateinit var id: String

    @Column(name = "scope_id", nullable = false, length = 36)
    lateinit var scopeId: String

    @Column(name = "storage_key", nullable = false, length = 64)
    lateinit var storageKey: String

    @Column(name = "chunk_count", nullable = false)
    var chunkCount: Int = 0

    @Column(name = "byte_size", nullable = false)
    var byteSize: Long = 0

    @Column(name = "wrapped_blob_key", nullable = false)
    lateinit var wrappedBlobKey: String

    @Column(name = "manifest")
    var manifest: String? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    companion object : PanacheCompanionBase<BlobEntity, String>
}

/**
 * A media (cr-0028): links a blob to a node (MEMORY/CHAPTER). The share grain stays the Memory's `K_mem`;
 * this row is only the blob→node link. `blobId` is the **original** (pristine) blob; role-tagged derivative
 * tiers link via [MediaDerivativeEntity] (cr-0045).
 */
@Entity
@Table(name = "veyq_media")
class MediaEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", length = 36)
    lateinit var id: String

    @Column(name = "scope_id", nullable = false, length = 36)
    lateinit var scopeId: String

    @Column(name = "node_id", nullable = false, length = 36)
    lateinit var nodeId: String

    @Column(name = "blob_id", nullable = false, length = 36)
    lateinit var blobId: String

    /** The original's `{v,w,h,capturedAt,capturedAtOverride,capturedAtSource,gpsLat,gpsLng}` listed-metadata tier sealed under `K_mem` (opaque base64); always listed, for grid layout + date sorting + the place medoid (cr-0068, consolidates cr-0059/cr-0064 layout + cr-0066 capturedAt). */
    @Column(name = "wrapped_meta")
    var wrappedMeta: String? = null

    /** The original's heavy EXIF bundle sealed under `K_mem` (opaque base64); lazy-loaded by the lightbox, never listed (cr-0064; GPS moved to `wrappedMeta` in cr-0068). */
    @Column(name = "wrapped_exif")
    var wrappedExif: String? = null

    /** The anonymous **contributor identity** that added this media on a CONTRIBUTOR share (cr-0101); null for curator uploads. Opaque link only. */
    @Column(name = "share_identity_id", length = 36)
    var shareIdentityId: String? = null

    /** cr-0113: the bucket this media lives in, or null = the uploader's primary (resolved lazily). en.memory.buckets, ADR 0034. */
    @Column(name = "bucket_id", length = 36)
    var bucketId: String? = null

    /** cr-0113: the uploader's opaque owner_key ("user:{id}" | "share:{id}"), copied on attach — the buckets attribution (denormalized). */
    @Column(name = "created_by", length = 64)
    var createdBy: String? = null

    /** When this media last changed — set `= createdAt` at attach, bumped to `now` on a meta edit (cr-0068). The
     *  `(updatedAt, mediaId)` pair is the gallery's keyset cursor for the live-updates delta (cr-0107, ADR 0033):
     *  a poll pulls only media past its cursor. Nullable in the DB (V33 backfills `= created_at`); always set by
     *  the write paths, and the read path coalesces `updatedAt ?: createdAt` defensively. */
    @Column(name = "updated_at")
    var updatedAt: Instant? = null

    /** When this media was trashed (soft-deleted), or null for a live media (uc.memory.delete-media, cr-0110). A
     *  trashed row is excluded from listNodeMedia (the active delta + the id-set); the MediaTrashPurge job
     *  hard-deletes it past the retention window; restore clears it. */
    @Column(name = "deleted_at")
    var deletedAt: Instant? = null

    /** Who trashed this media — the member's user id (the deletion audit), or null for a live media (cr-0110). */
    @Column(name = "deleted_by", length = 36)
    var deletedBy: String? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    companion object : PanacheCompanionBase<MediaEntity, String>
}

/**
 * A media derivative (en.memory.derivatives, cr-0045): a role-tagged (THUMBNAIL/PREVIEW) blob of a media,
 * generated + sealed client-side. Each derivative is its **own** blob with its own `K_blob` wrap — never a
 * key shared with the original or the other tier. Operator-blind: only the opaque role tag + a blob link.
 */
@Entity
@Table(name = "veyq_media_derivative")
class MediaDerivativeEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", length = 36)
    lateinit var id: String

    @Column(name = "media_id", nullable = false, length = 36)
    lateinit var mediaId: String

    @Column(name = "kind", nullable = false, length = 16)
    lateinit var kind: String

    @Column(name = "blob_id", nullable = false, length = 36)
    lateinit var blobId: String

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    companion object : PanacheCompanionBase<MediaDerivativeEntity, String>
}

/**
 * A per-uploader **bucket** in a Memory node (en.memory.buckets, cr-0113): the attribution axis (who contributed
 * a medium), orthogonal to Chapters. Keyed by an opaque [ownerKey] (`user:{userId}` | `share:{shareIdentityId}`).
 * A lazy virtual **primary** ([isPrimary] = true, [parentBucketId] null) per uploader + optional one-level
 * **sub-buckets** ([parentBucketId] = the primary). The name is sealed under `K_mem` ([sealedName], opaque
 * base64) — null for a member's primary (the client shows the operator-visible username). Primary uniqueness
 * `(node_id, owner_key)` is enforced in code (ADR 0034; no DB partial index in the H2 ∩ MariaDB subset).
 */
@Entity
@Table(name = "veyq_bucket")
class BucketEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", length = 36)
    lateinit var id: String

    @Column(name = "scope_id", nullable = false, length = 36)
    lateinit var scopeId: String

    @Column(name = "node_id", nullable = false, length = 36)
    lateinit var nodeId: String

    @Column(name = "owner_key", nullable = false, length = 64)
    lateinit var ownerKey: String

    @Column(name = "parent_bucket_id", length = 36)
    var parentBucketId: String? = null

    @Column(name = "sealed_name")
    var sealedName: String? = null

    @Column(name = "is_primary", nullable = false)
    var isPrimary: Boolean = false

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    companion object : PanacheCompanionBase<BucketEntity, String>
}
