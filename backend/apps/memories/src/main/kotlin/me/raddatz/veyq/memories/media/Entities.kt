package me.raddatz.veyq.memories.media

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import org.hibernate.annotations.DynamicUpdate

/**
 * A media (cr-0028): links a blob to a node (MEMORY/CHAPTER). The share grain stays the Memory's `K_mem`;
 * this row is only the blob→node link. `blobId` is the **original** (pristine) blob; role-tagged derivative
 * tiers link via [MediaDerivativeEntity] (cr-0045).
 */
@Entity
@Table(name = "veyq_media")
@DynamicUpdate
class MediaEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "scope_id", nullable = false, columnDefinition = "uuid")
    lateinit var scopeId: String

    @Column(name = "node_id", nullable = false, columnDefinition = "uuid")
    lateinit var nodeId: String

    @Column(name = "blob_id", nullable = false, columnDefinition = "uuid")
    lateinit var blobId: String

    /** The original's `{v,w,h,capturedAt,capturedAtOverride,capturedAtSource,gpsLat,gpsLng}` listed-metadata tier sealed under `K_mem` (opaque base64); always listed, for grid layout + date sorting + the place medoid (cr-0068, consolidates cr-0059/cr-0064 layout + cr-0066 capturedAt). */
    @Column(name = "wrapped_meta")
    var wrappedMeta: String? = null

    /** How many times [wrappedMeta] was replaced (ADR 0077): 0 at attach, bumped only by the compare-and-set in
     *  `MediaStore.setMeta`. Not the node's item revision (core's `NodeItemsRevisions`), which every attach and trash also moves. */
    @Column(name = "meta_version", nullable = false)
    var metaVersion: Long = 0

    /**
     * The original's **lazy, panel-only** metadata bundle sealed under `K_mem` (opaque base64); fetched per item
     * when a detail panel opens, never listed (cr-0064). A versioned **client-only** shape the server never
     * parses, so what belongs in it is decided client-side and is deliberately not enumerated here — an
     * enumeration in a place that cannot read the thing only goes stale. The listed essentials (geometry,
     * capture time, GPS, the resolved place name) live in `wrappedMeta`; GPS moved there in cr-0068.
     *
     * This KDoc is the **live** copy of that rule.
     */
    @Column(name = "wrapped_exif")
    var wrappedExif: String? = null

    /** The anonymous **contributor identity** that added this media on a READ_WRITE share (cr-0101); null for curator uploads. Opaque link only. */
    @Column(name = "share_identity_id", columnDefinition = "uuid")
    var shareIdentityId: String? = null

    /** cr-0113: the bucket this media lives in, or null = the uploader's primary (resolved lazily). en.memory.buckets, ADR 0034. */
    @Column(name = "bucket_id", columnDefinition = "uuid")
    var bucketId: String? = null

    /** cr-0113: the uploader's opaque owner_key ("user:{id}" | "share:{id}"), copied on attach — the buckets attribution (denormalized). */
    @Column(name = "created_by", length = 64)
    var createdBy: String? = null

    /** When this media last changed — the attach time at attach, bumped to `now` on a meta edit (cr-0068) and a
     *  restore (cr-0110). The `(updatedAt, mediaId)` pair is the gallery's keyset cursor for the live-updates delta
     *  (cr-0107, ADR 0033): a poll pulls only media past its cursor. The medium's only stored time besides
     *  [deletedAt]: a separate creation time was read only as this column's fallback, so it is not stored
     *  (inbox #398). */
    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant

    /** When this media was trashed (soft-deleted), or null for a live media (uc.memory.delete-media, cr-0110). A
     *  trashed row is excluded from listNodeMedia (the active delta + the id-set); the MediaTrashPurge job
     *  hard-deletes it past the retention window; restore clears it. */
    @Column(name = "deleted_at")
    var deletedAt: Instant? = null

    /** Who trashed this media — the member's user id (the deletion audit), or null for a live media (cr-0110). */
    @Column(name = "deleted_by", columnDefinition = "uuid")
    var deletedBy: String? = null

    companion object : PanacheCompanionBase<MediaEntity, String>
}

/**
 * A media derivative (en.memory.derivatives, cr-0045): a role-tagged (THUMBNAIL/PREVIEW) blob of a media,
 * generated + sealed client-side. Each derivative is its **own** blob with its own `K_blob` wrap — never a
 * key shared with the original or the other tier. Operator-blind: only the opaque role tag + a blob link.
 */
@Entity
@Table(name = "veyq_media_derivative")
@DynamicUpdate
class MediaDerivativeEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "media_id", nullable = false, columnDefinition = "uuid")
    lateinit var mediaId: String

    @Column(name = "kind", nullable = false, length = 16)
    lateinit var kind: String

    @Column(name = "blob_id", nullable = false, columnDefinition = "uuid")
    lateinit var blobId: String

    companion object : PanacheCompanionBase<MediaDerivativeEntity, String>
}

/**
 * A per-uploader **bucket** in a Memory node (en.memory.buckets, cr-0113): the attribution axis (who contributed
 * a medium), orthogonal to Chapters. Keyed by an opaque [ownerKey] (`user:{userId}` | `share:{shareIdentityId}`).
 * A lazy virtual **primary** ([isPrimary] = true, [parentBucketId] null) per uploader + optional one-level
 * **sub-buckets** ([parentBucketId] = the primary). The name is sealed under `K_mem` ([sealedName], opaque
 * base64) — null for a member's primary (the client shows the operator-visible username). Primary uniqueness
 * `(node_id, owner_key)` is held by the node row's lock in `MediaStore.ensurePrimaryBucket` (ADR 0034 §4).
 */
@Entity
@Table(name = "veyq_bucket")
@DynamicUpdate
class BucketEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "scope_id", nullable = false, columnDefinition = "uuid")
    lateinit var scopeId: String

    @Column(name = "node_id", nullable = false, columnDefinition = "uuid")
    lateinit var nodeId: String

    @Column(name = "owner_key", nullable = false, length = 64)
    lateinit var ownerKey: String

    @Column(name = "parent_bucket_id", columnDefinition = "uuid")
    var parentBucketId: String? = null

    @Column(name = "sealed_name")
    var sealedName: String? = null

    @Column(name = "is_primary", nullable = false)
    var isPrimary: Boolean = false

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    companion object : PanacheCompanionBase<BucketEntity, String>
}
