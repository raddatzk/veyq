package me.raddatz.veyq.core.node

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.hibernate.annotations.DynamicUpdate

/**
 * A node in a scope's encrypted tree (en.memory.tree, cr-0027). FOLDER organizes only and is core's; every other
 * `kind` belongs to an application, which places it through `NodeKindRules` (ADR 0079). Every node, FOLDER included,
 * carries its own content key (`wrappedContentKey`, wrapped under its parent's content key, or under `K_SC` at the
 * node's epoch at the scope root — ADR 0080 Decision 1), and every node may carry one versioned sealed payload (`wrappedMeta`/`metaRevision`) whose format is its kind's application's.
 * Operator-blind: `encryptedName` / `wrappedContentKey` / `wrappedMeta` are opaque ciphertext, `slugToken` a blinded
 * keyed MAC. `parentId`
 * is the **parent slot** — the parent node id, or the scope id for a root-level node (so slug uniqueness per
 * parent works at the root too); it is not a single-table FK.
 */
@Entity
@Table(name = "veyq_node")
@DynamicUpdate
class NodeEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "scope_id", nullable = false, columnDefinition = "uuid")
    lateinit var scopeId: String

    @Column(name = "parent_id", nullable = false, columnDefinition = "uuid")
    lateinit var parentId: String

    @Column(name = "kind", nullable = false, length = 16)
    lateinit var kind: String

    @Column(name = "encrypted_name", nullable = false)
    lateinit var encryptedName: String

    @Column(name = "slug_token", nullable = false, length = 128)
    lateinit var slugToken: String

    @Column(name = "order_index", nullable = false)
    var orderIndex: Int = 0

    /** The scope content-key **epoch** this node's name/slug/content-key wrap are sealed under (ADR 0024, cr-0088);
     *  0 = the pre-rotation baseline. A member reads it iff they hold an epoch ≥ this. */
    @Column(name = "epoch", nullable = false)
    var epoch: Int = 0

    /** The node's content key wrapped under its parent's (ADR 0080 Decision 1): every node has one, `NOT NULL`. */
    @Column(name = "wrapped_content_key", nullable = false)
    lateinit var wrappedContentKey: String

    @Column(name = "wrapped_meta")
    var wrappedMeta: String? = null

    /** How many times [wrappedMeta] was replaced (ADR 0077): 0 at birth, bumped only by the compare-and-set in
     *  `NodesStore.applyUpdate`, which every meta write goes through. Not a node's item-list revision (`veyq_node_items_revision`), which every attach and trash of an item also moves. */
    @Column(name = "meta_revision", nullable = false)
    var metaRevision: Long = 0

    /** How many times the node was moved or renamed (ADR 0080 Decision 3): 0 at birth, bumped only by the
     *  compare-and-set in `NodesStore.applyUpdate`, which every placement write (parent, name, slug, key wrap) goes
     *  through. The `node-name` and `content-key` seals bind it, so a wrap or a name is valid at exactly one revision. */
    @Column(name = "placement_revision", nullable = false)
    var placementRevision: Long = 0

    /** The drop private key of the shared root node of a WRITE_ONLY link, sealed under the node's content key (opaque
     *  base64) — set once, by core inside the node's first WRITE_ONLY share, never replaced: what WRITE_ONLY guests
     *  added opens through it (ADR 0031 point 7). A field of its own, never part of [wrappedMeta]. */
    @Column(name = "sealed_drop_private_key")
    var sealedDropPrivateKey: String? = null

    /** Set on a guest's **identity folder** only (ADR 0080 decision 11): the `FOLDER` a `WRITE_ONLY` guest's identity
     *  holds in its link's root (a drop box, e.g. Depot's `DEPOT_DROPBOX`), created by the application's own operation
     *  through [me.raddatz.veyq.core.sharing.IdentityFolders] — one per (parent, identity), `ux_node_parent_identity`.
     *  Its name, slug and key wrap are under that identity's key `K_up`, not the parent's; a reader opens the parent's
     *  [sealedDropPrivateKey], then the identity's wrapped `K_up`, then the entry. Inside such a folder only that
     *  identity creates (`NodesHandler.createInScope`); a placement clears it (the only placement it can take is a
     *  member's move out, which re-wraps the key under the new parent). Kept on a trashed row: the trash and a restore
     *  still need the identity's key to open the entry, and `NodeGuestIdentityUsage` keeps the identity alive. */
    @Column(name = "share_identity_id", columnDefinition = "uuid")
    var shareIdentityId: String? = null

    /** When this node was soft-deleted (uc.memory.delete-node, ADR 0061), or null while it is live. Set on the
     *  node **and every descendant** in one transaction; cleared by a restore. Every user- and guest-facing read
     *  carries `deletedAt is null` — three deliberately do not (slug uniqueness, the delete's own traversal, and
     *  the whole-space erase), each documented at its site. */
    @Column(name = "deleted_at")
    var deletedAt: Instant? = null

    /** When the server stored the node — server-observed, to the millisecond (`TIMESTAMP(3)`), set once at creation
     *  ([birthInstant]) and never written again: a rename, a move, a trash and a restore all keep it, and no request
     *  carries it. Members' tree listing serves it (`NodeSummary.createdAt`), and clients order equal-named siblings by
     *  (createdAt, id) (ADR 0015). Never on a guest's share listing nor the trash listing. */
    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    companion object : PanacheCompanionBase<NodeEntity, String> {
        /** Core's one node kind. Every other kind is an application's, named by that application (ADR 0079). */
        const val FOLDER = "FOLDER"

        /** A new node's [createdAt]: now, cut to the column's millisecond. Cut HERE, not by the database: MariaDB drops the
         *  digits past the column's precision while H2 rounds them, so an uncut write would list a different instant on
         *  each engine. Cut, both store and list exactly this value. */
        fun birthInstant(): Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)
    }
}

/**
 * A scope's tree revision (ADR 0081) — one monotonic counter per scope, the cheap "did anything a level view shows
 * change?" signal behind the node listing's conditional GET. **No row reads as 0**: the scope's first change births it
 * (`NodesStore.bumpTree`), and the node eraser (phase 700) deletes it before the scope row goes. A row of its own, not
 * a column of `veyq_scope`: the scope row is the tree's lock (`NodesStore.lockScope`), and a bump that took it again
 * after a node row would invert the lock order. Opaque operational metadata (a number): operator-blind.
 */
@Entity
@Table(name = "veyq_scope_tree_revision")
@DynamicUpdate
class ScopeTreeRevisionEntity : PanacheEntityBase {
    @Id
    @Column(name = "scope_id", columnDefinition = "uuid")
    lateinit var scopeId: String

    @Column(name = "revision", nullable = false)
    var revision: Long = 0

    companion object : PanacheCompanionBase<ScopeTreeRevisionEntity, String>
}
