package me.raddatz.veyq.core.blob

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import org.hibernate.annotations.DynamicUpdate

/**
 * An OPEN upload session (cr-0028): opened by `start` (which presigns the first window of PUTs under
 * `storageKey`), extended by a window request per further window, consumed by `complete` (stat + attach).
 * Operator-blind: `wrappedBlobKey` is opaque ciphertext; `storageKey` is a random object-key prefix. There is no
 * status column: a row exists ⇒ the session is OPEN.
 *
 * **Bound to its opener** (ADR 0013 §2): exactly one of [openerUserId] (a member upload) and
 * [openerShareIdentityId] (a contributor upload) is set, and every read that acts on the session —
 * [BlobUploadStore.findSession] — matches it. A row from before the columns existed has neither and matches nobody.
 * [lastWindowAt] is the session's liveness: set at `start`, touched by every window, and what the stale-session
 * purge keys on.
 *
 * This row is the **only** pointer to its `storage_key`, so nothing else can find the chunks a client staged
 * under it — reclaiming it and reclaiming those objects is one job, and it is [UploadSessionPurge]'s
 * (`veyq.blob.upload.retention`). This KDoc is the live statement of the rule.
 */
@Entity
@Table(name = "veyq_upload_session")
@DynamicUpdate
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

    /** The blob's metadata sealed under its own `K_blob` (the `blob-meta` seal), as `start` declared it; opaque,
     *  optional. Written once — `updatable = false` keeps it out of every UPDATE — and copied to the blob row. */
    @Column(name = "wrapped_meta", updatable = false)
    var wrappedBlobMeta: String? = null

    @Column(name = "opener_user_id", length = 36)
    var openerUserId: String? = null

    @Column(name = "opener_share_identity_id", length = 36)
    var openerShareIdentityId: String? = null

    @Column(name = "last_window_at", nullable = false)
    lateinit var lastWindowAt: Instant

    /** The client-minted id the blob will be stored under — its `K_blob` wrap is sealed bound to it (ADR 0072). */
    @Column(name = "blob_id", length = 36)
    var blobId: String? = null

    /** What the blob belongs to — the client-minted media id, or the node for a cover (ADR 0072). */
    @Column(name = "owner_id", length = 36)
    var ownerId: String? = null

    /** The role the wrap is sealed bound to (ADR 0072): a token the owning application defines; core compares it, never names one. */
    @Column(name = "blob_role", length = 16)
    var blobRole: String? = null

    /** Would this session's wrap open where it is about to be attached? Null ids never match. */
    fun boundTo(ownerId: String, role: String): Boolean = this.ownerId == ownerId && blobRole == role && blobId != null

    companion object : PanacheCompanionBase<UploadSessionEntity, String>
}

/**
 * A completed blob (cr-0028): the chunk `manifest` (per-chunk ciphertext sizes, JSON array, or null when the
 * dev store has no transport) + the `K_blob` wrap + the blob's sealed metadata. The bytes live in the object store under `storageKey/<i>`,
 * never here. Operator-blind throughout.
 */
@Entity
@Table(name = "veyq_blob")
@DynamicUpdate
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

    /**
     * The blob's metadata (type, name, modification time) sealed by the client under the blob's own `K_blob` — the
     * `blob-meta` seal — copied from the upload session; opaque, optional (null when the upload declared none).
     * **Write-once:** `updatable = false` keeps it out of every UPDATE Hibernate writes, and no operation names it,
     * so what a read ref serves is what the upload declared.
     */
    @Column(name = "wrapped_meta", updatable = false)
    var wrappedBlobMeta: String? = null

    @Column(name = "manifest")
    var manifest: String? = null

    companion object : PanacheCompanionBase<BlobEntity, String>
}
