package com.veyq.core.node

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import org.hibernate.annotations.DynamicUpdate

/**
 * A node in a scope's encrypted tree (en.memory.tree, cr-0027). FOLDER organizes only and is core's; every other
 * `kind` belongs to an application, which places it through `NodeKindRules` (ADR 0079) — Memories' MEMORY is the
 * content unit (its `wrappedContentKey` = K_mem under K_SC), its CHAPTER sits inside a Memory. Operator-blind:
 * `encryptedName` / `wrappedContentKey` are opaque ciphertext, `slugToken` a blinded keyed MAC. `parentId`
 * is the **parent slot** — the parent node id, or the scope id for a root-level node (so slug uniqueness per
 * parent works at the root too); it is not a single-table FK.
 */
@Entity
@Table(name = "veyq_node")
@DynamicUpdate
class NodeEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", length = 36)
    lateinit var id: String

    @Column(name = "scope_id", nullable = false, length = 36)
    lateinit var scopeId: String

    @Column(name = "parent_id", nullable = false, length = 36)
    lateinit var parentId: String

    @Column(name = "kind", nullable = false, length = 16)
    lateinit var kind: String

    @Column(name = "encrypted_name", nullable = false)
    lateinit var encryptedName: String

    @Column(name = "slug_token", nullable = false, length = 128)
    lateinit var slugToken: String

    @Column(name = "order_index", nullable = false)
    var orderIndex: Int = 0

    /** The scope content-key **epoch** this node's name/slug/`K_mem`-wrap are sealed under (ADR 0024, cr-0088);
     *  0 = the pre-rotation baseline. A member reads it iff they hold an epoch ≥ this. */
    @Column(name = "epoch", nullable = false)
    var epoch: Int = 0

    @Column(name = "wrapped_content_key")
    var wrappedContentKey: String? = null

    @Column(name = "wrapped_meta")
    var wrappedMeta: String? = null

    /** How many times [wrappedMeta] was replaced (ADR 0077): 0 at birth, bumped only by the compare-and-set in
     *  `NodesStore.applyUpdate`, which every meta write goes through. Not [mediaRevision], which every attach and trash also moves. */
    @Column(name = "meta_version", nullable = false)
    var metaVersion: Long = 0

    /** A MEMORY's drop private key sealed under its `K_mem` (opaque base64) — set once, by its first UPLOADER share,
     *  never replaced: the media UPLOADER guests added open through it (ADR 0031 point 7). */
    @Column(name = "sealed_drop_private_key")
    var sealedDropPrivateKey: String? = null

    /** The node's standalone **cover** blob id (cr-0073): a veyq_blob with NO veyq_media row (never in the gallery), or null. Opaque — the server never learns it is "the cover" (that stays in the sealed `wrappedMeta.cover`). */
    @Column(name = "cover_blob_id", length = 36)
    var coverBlobId: String? = null

    /** The cover blob's ciphertext size in bytes (cr-0073), for the byte budget + retention; null when no cover blob. */
    @Column(name = "cover_byte_size")
    var coverByteSize: Long? = null

    /** Live-updates change signal (cr-0107, ADR 0033): a monotonic per-node counter bumped by **every**
     *  gallery-media mutation — attach, meta-edit, delete — but **not** a cover change. Doubles as the
     *  `listNodeMedia` ETag; an unchanged poll is an O(1) compare → a 304 (no media query). */
    @Column(name = "media_revision", nullable = false)
    var mediaRevision: Int = 0

    /** Live-updates delete gate (cr-0107, ADR 0033): bumped **only** by a delete — so a delete moves **both**
     *  counters, an add/edit only [mediaRevision]. Gates the O(N) id-set delete-reconcile: the server returns
     *  the current mediaId set only when a client's echoed value lags this. On the same row as [mediaRevision],
     *  so one read is a consistent pair. */
    @Column(name = "delete_revision", nullable = false)
    var deleteRevision: Int = 0

    /** When this node was soft-deleted (uc.memory.delete-node, ADR 0061), or null while it is live. Set on the
     *  node **and every descendant** in one transaction; cleared by a restore. Every user- and guest-facing read
     *  carries `deletedAt is null` — three deliberately do not (slug uniqueness, the delete's own traversal, and
     *  the whole-space erase), each documented at its site. */
    @Column(name = "deleted_at")
    var deletedAt: Instant? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    companion object : PanacheCompanionBase<NodeEntity, String> {
        /** Core's one node kind. Every other kind is an application's, named by that application (ADR 0079). */
        const val FOLDER = "FOLDER"
    }
}
