package me.raddatz.veyq.core.applicationblob

import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.blob.BlobUploadStore
import me.raddatz.veyq.core.blob.UploadSessionEntity
import me.raddatz.veyq.core.identity.PrincipalKey
import me.raddatz.veyq.core.items.ItemsCursor
import me.raddatz.veyq.core.items.ItemsPage
import me.raddatz.veyq.core.items.NodeItemsRevisions
import me.raddatz.veyq.core.moderation.block.ContentView
import me.raddatz.veyq.core.moderation.hold.HeldRow
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.node.rekey.NodeEpochGate
import io.quarkus.arc.All
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import jakarta.transaction.Transactional
import java.time.Duration
import java.time.Instant

/** What an attach of an application blob did. Nothing is stored on any answer but [Attached]. */
sealed interface BlobAttach {
    data class Attached(val row: ApplicationBlobEntity) : BlobAttach
    /** The caller's gate refused under its locks, or the session was spent by a concurrent completion. */
    object NotAdmitted : BlobAttach
    /** The id is taken, or the application refused the owner in this node. */
    object IdTaken : BlobAttach
    /** The owner holds its role's count of unreleased blobs already. */
    object TooMany : BlobAttach
    /** No live node of that id in the scope whose application stores blobs through the service, or not in that role. */
    object NotFound : BlobAttach
    /** The node's epoch refused the stated one: `stale_epoch` or `node_rekey_pending`; the session stays open. */
    data class EpochRefused(val code: String) : BlobAttach
}

/** An application blob as a read serves it: its row, its blob row, when the purge erases it, and whether a block reaches it. */
data class ApplicationBlobRow(
    val row: ApplicationBlobEntity, val blob: BlobEntity, val manifest: List<Long>?, val purgeAfter: Instant?, val blocked: Boolean,
)

/** An application blob as an application's write sees it ([ApplicationBlobs.lock]): which node and owner hold it. */
data class ApplicationBlobRef(val id: String, val scopeId: String, val nodeId: String, val ownerId: String)

/** Where the blob row of an owner's content comes from at [ApplicationBlobs.attachIn]. */
sealed interface BlobSource {
    /** A verified upload session the caller has spent in its write, with its verified manifest: the service writes its blob row. */
    data class Upload(val session: UploadSessionEntity, val manifest: List<Long>?) : BlobSource

    /** A blob row the caller wrote on a stored object its node already holds (`SharedBlobs.persist`, ADR 0013 § 5). */
    data class Shared(val blobId: String) : BlobSource
}

/** What [ApplicationBlobs.attachIn] did. Only [Attached] wrote a row; on any other answer the caller rolls its write back. */
sealed interface OwnedAttach {
    data class Attached(val row: ApplicationBlobEntity) : OwnedAttach

    /** The role is none the node's application stores as an owner's content, the upload was started for another owner
     *  or role (its wrap would not open here), a guest may not add the role, or the owner holds its role's count already. */
    object Mismatch : OwnedAttach

    /** The blob's id is taken. */
    object IdTaken : OwnedAttach
}

/** One application blob's wrap as a node's re-key reads it ([ApplicationBlobs.rekeyPage]): the id its wrap is bound to. */
data class ApplicationBlobRekeyRow(val blobId: String, val role: String, val wrapOwner: String, val wrappedBlobKey: String)

/**
 * Core's blob service (ADR 0112): the blobs an application stores — attached by their completion
 * ([ApplicationBlobUploadCompletion] → [attach]), kept and released by the application's own writes ([keep],
 * [releaseOwners], [release]), erased after the trash retention ([ApplicationBlobPurge]) or with their node and space.
 * Every writer calls `items.bump` after the rows it changed, so an application's open list learns of the change by its
 * node's item revision.
 *
 * **Lock order, every writer of a node's blobs:** (1) a guest's re-check under the scope and share locks (a
 * completion's `admitted`), or the scope row (the purge); (2) the application's own rows ([ApplicationBlobOwners.lockOwner],
 * an application's write); (3) the service's rows ([lock]); (4) the node's row ([NodeEpochGate.check]); (5) the blob
 * rows (`StoredObjectLocks`, item rows before blob rows); (6) the item-revision row ([NodeItemsRevisions.bump]). An
 * application's write that keeps or releases takes (2) and (3) before its own node lock, and calls [keep] / [release]
 * after it — bulk updates that also reach a row a completion committed in between, under the node's lock.
 */
@ApplicationScoped
class ApplicationBlobs(
    private val epochs: NodeEpochGate,
    private val items: NodeItemsRevisions,
    private val blobs: BlobUploadStore,
    // `MutableList`, not `List`: ArC refuses `@All kotlin.List` (see `ScopePurge`'s constructor).
    @All owners: MutableList<ApplicationBlobOwners>,
) {
    private val owners: List<ApplicationBlobOwners> = owners

    /** The one application that stores the blobs of a node of [nodeKind]; none or several → null (stores none). */
    fun ownersOf(nodeKind: String): ApplicationBlobOwners? = owners.filter { it.accepts(nodeKind) }.singleOrNull()

    /** Whether a node of [nodeKind] stores blobs through the service, in either form. */
    fun stores(nodeKind: String): Boolean = ownersOf(nodeKind) != null

    /** Whether a node of [nodeKind] stores a blob that is an item of its own through the service — what its own
     *  completion, guest route and reads serve (ADR 0113 decision 2). */
    fun storesItems(nodeKind: String): Boolean = ownersOf(nodeKind)?.roles()?.values?.any { it.ownItem } == true

    /** Whether [role] is an item of its own in a node of [nodeKind] (false for an owner's content, and for a role the
     *  node's application stores none in). */
    fun isOwnItem(nodeKind: String, role: String): Boolean = roleOf(nodeKind, role)?.ownItem == true

    /** Whether no application declares [role] as an owner's content: a held row read without its node (a case read). */
    fun neverOwnersContent(role: String): Boolean = owners.none { it.roles()[role]?.ownItem == false }

    /**
     * Whether a guest of a share reaches [row] of a node of [nodeKind] (the share's blob predicate): an item of its own
     * always (an application's history reads a released one); an owner's content only while it is not released — a
     * blob released with its owner's end (a trashed medium) is in no share's reach (ADR 0113 decision 6).
     */
    fun reachesGuests(row: ApplicationBlobEntity, nodeKind: String?): Boolean {
        val rule = nodeKind?.let { roleOf(it, row.role) } ?: return false
        return rule.ownItem || row.releasedAt == null
    }

    /** The node kind of each of [nodeIds] (live or trashed) that has a row. */
    fun kindsOf(nodeIds: Collection<String>): Map<String, String> =
        nodeIds.distinct().chunked(CHUNK).flatMap { NodeEntity.list("id in ?1", it) }.associate { it.id to it.kind }

    /** The rule of [role] for a node of [nodeKind], or null when its application stores no blob in that role. */
    fun roleOf(nodeKind: String, role: String): ApplicationBlobRole? = ownersOf(nodeKind)?.roles()?.get(role)

    /** Whether some application storing through the service lets a guest add [role] as an item of its own (its route). */
    fun anyGuestRole(role: String): Boolean = owners.any { it.roles()[role]?.let { r -> r.guestMayAdd && r.ownItem } == true }

    /** Whether a guest may add a blob in [role], an item of its own, to a node of [nodeKind] (the service's guest route). */
    fun guestMayAdd(nodeKind: String, role: String): Boolean = roleOf(nodeKind, role)?.let { it.guestMayAdd && it.ownItem } == true

    // --- the completion ------------------------------------------------------------------------------------------

    /**
     * Attach the verified [session] as the blob [id] in [role] held by [ownerId] in the node [nodeId], by [writer], all or
     * none: the node live and at [nodeEpoch], of a kind whose application stores [role] through the service, the owner
     * admitted by it, the id free, the owner under its count, the session spent and its blob row written, the row
     * inserted, the node's list moved. [admitted], a guest's re-check of its link and target, runs first; [attached] is
     * told the id last, in this transaction (the guest-write record).
     */
    @Transactional
    fun attach(
        scopeId: String, nodeId: String, nodeEpoch: Int, id: String, ownerId: String, role: String, session: UploadSessionEntity,
        manifest: List<Long>?, writer: PrincipalKey, admitted: () -> Boolean, attached: (String) -> Unit,
    ): BlobAttach {
        if (!admitted()) return BlobAttach.NotAdmitted
        val kind = NodeEntity.findById(nodeId)?.kind ?: return BlobAttach.NotFound
        val application = ownersOf(kind) ?: return BlobAttach.NotFound
        val rule = application.roles()[role] ?: return BlobAttach.NotFound
        application.lockOwner(ownerId) // (2) the application's rows, before the node's
        when (epochs.check(scopeId, nodeId, nodeEpoch)) {
            NodeEpochGate.Verdict.MISSING -> return BlobAttach.NotFound
            NodeEpochGate.Verdict.STALE -> return BlobAttach.EpochRefused(STALE_EPOCH)
            NodeEpochGate.Verdict.PENDING -> return BlobAttach.EpochRefused(NODE_REKEY_PENDING)
            NodeEpochGate.Verdict.OK -> Unit
        }
        // Refreshed under the lock by the gate: a trash or a kind that committed before it is seen here.
        val node = NodeEntity.findById(nodeId) ?: return BlobAttach.NotFound
        if (node.kind != kind || node.deletedAt != null) return BlobAttach.NotFound
        // Read under the node's lock, which every inserter of this node's blobs and of its application's records holds:
        // what these return is every row committed before.
        val state = when (val a = application.admit(scopeId, nodeId, ownerId, id)) {
            is OwnerAdmission.Admitted -> a.state
            OwnerAdmission.Taken -> return BlobAttach.IdTaken
        }
        if (ApplicationBlobEntity.count("id", id) > 0) return BlobAttach.IdTaken
        val held = ApplicationBlobEntity.count("nodeId = ?1 and ownerId = ?2 and role = ?3 and releasedAt is null", nodeId, ownerId, role)
        if (held >= rule.maxPerOwner) return BlobAttach.TooMany
        if (UploadSessionEntity.delete("id = ?1", session.id) != 1L) {
            QuarkusTransaction.setRollbackOnly() // spent by a concurrent completion that committed first
            return BlobAttach.NotAdmitted
        }
        val blobId = blobs.persistBlob(session, manifest)
        val now = Instant.now()
        val row = ApplicationBlobEntity().apply {
            this.id = id
            this.scopeId = scopeId
            this.nodeId = nodeId
            this.ownerId = ownerId
            this.role = role
            this.blobId = blobId
            writerUserId = writer.userId
            writerIdentityId = writer.identityId
            createdAt = now
            keptAt = if (state == OwnerState.NEW) null else now
            releasedAt = if (state == OwnerState.ENDED) now else null
        }
        row.persist()
        items.bump(scopeId, nodeId) // after the blob row: record rows -> node row -> revision row
        attached(id)
        return BlobAttach.Attached(row)
    }

    /**
     * Attach one blob of an owner's content (ADR 0113 decision 4) inside its application's own write, which creates or
     * writes the owner [ownerId] in the same transaction (Memories: a medium born with its original and derivatives):
     * the blob [source] in [role] of the node [nodeId] of [nodeKind], by [writer], born in [state] at [now]. The caller
     * holds the transaction and the node's lock (it judged the node's epoch under it, after its own rows: the service's
     * lock order), found the node live, and verified and spent any upload session. The service checks what is its: the
     * role is an owner's content of the node's application, an upload was started for this owner in this role (the
     * wrap's binding), a guest writer may add the role, the owner is under its count, and the blob's id — the row's id —
     * is free. Then it writes the blob row (for an upload) and its own row. It moves no item revision: the caller's
     * write bumps once. On any answer but [OwnedAttach.Attached] the caller rolls its write back.
     */
    fun attachIn(
        scopeId: String, nodeId: String, nodeKind: String, ownerId: String, role: String, source: BlobSource,
        writer: PrincipalKey, state: OwnerState, now: Instant,
    ): OwnedAttach {
        val rule = roleOf(nodeKind, role)?.takeIf { !it.ownItem } ?: return OwnedAttach.Mismatch
        if (writer.identityId != null && !rule.guestMayAdd) return OwnedAttach.Mismatch
        val blobId = when (source) {
            is BlobSource.Upload -> {
                if (!source.session.boundTo(rule.wrapOwner(nodeId, ownerId), role)) return OwnedAttach.Mismatch
                checkNotNull(source.session.blobId) { "a bound session names its blob" }
            }
            is BlobSource.Shared -> source.blobId
        }
        if (ApplicationBlobEntity.count("id", blobId) > 0) return OwnedAttach.IdTaken
        val held = ApplicationBlobEntity.count("nodeId = ?1 and ownerId = ?2 and role = ?3 and releasedAt is null", nodeId, ownerId, role)
        if (held >= rule.maxPerOwner) return OwnedAttach.Mismatch
        if (source is BlobSource.Upload) blobs.persistBlob(source.session, source.manifest)
        val row = ApplicationBlobEntity().apply {
            this.id = blobId // an owner's content has no id of its own on the wire: the row's is its blob's
            this.scopeId = scopeId
            this.nodeId = nodeId
            this.ownerId = ownerId
            this.role = role
            this.blobId = blobId
            writerUserId = writer.userId
            writerIdentityId = writer.identityId
            createdAt = now
            keptAt = if (state == OwnerState.NEW) null else now
            releasedAt = if (state == OwnerState.ENDED) now else null
        }
        row.persist()
        return OwnedAttach.Attached(row)
    }

    // --- an application's write ----------------------------------------------------------------------------------

    /**
     * Lock the rows [ownerIds] hold and the rows [ids] `FOR UPDATE`, in id order — step (3) of the lock order, in an
     * application's write after its own rows and before its node's — and answer each as a [ApplicationBlobRef]: what a
     * write checks a release against (Expenses: a dropped receipt of its own record in this Pot).
     */
    fun lock(ownerIds: Collection<String>, ids: Collection<String> = emptyList()): List<ApplicationBlobRef> {
        val byOwner = ownerIds.distinct().sorted().chunked(CHUNK).flatMap {
            ApplicationBlobEntity.find("ownerId in ?1 order by id", it).withLock(LockModeType.PESSIMISTIC_WRITE).list()
        }
        val named = ids.distinct().sorted().chunked(CHUNK).flatMap {
            ApplicationBlobEntity.find("id in ?1 order by id", it).withLock(LockModeType.PESSIMISTIC_WRITE).list()
        }
        return (byOwner + named).distinctBy { it.id }.map { ApplicationBlobRef(it.id, it.scopeId, it.nodeId, it.ownerId) }
    }

    /**
     * The owners [ownerIds] keep their blobs in [nodeId] as of [now] (ADR 0112 decision 2: Expenses' every revision that
     * is no tombstone): a blob never kept is kept, one released with its owner's end is kept again; one released by name
     * stays released. In the application's write, after [lock] and its node's lock; moves nothing on its own — the
     * write's own `items.bump` does.
     */
    fun keep(nodeId: String, ownerIds: Collection<String>, now: Instant) {
        ownerIds.distinct().sorted().chunked(CHUNK).forEach {
            ApplicationBlobEntity.update(
                "keptAt = coalesce(keptAt, ?1), releasedAt = null where nodeId = ?2 and ownerId in ?3 and (keptAt is not null or releasedAt is null)",
                now, nodeId, it,
            )
        }
    }

    /**
     * The owners [ownerIds] ended as of [now] (Expenses: a tombstone): every blob of theirs in [nodeId] not released yet
     * is released with them, and the owner's next [keep] keeps it again. As [keep], in the application's write.
     */
    fun releaseOwners(nodeId: String, ownerIds: Collection<String>, now: Instant) {
        ownerIds.distinct().sorted().chunked(CHUNK).forEach {
            ApplicationBlobEntity.update(
                "keptAt = coalesce(keptAt, ?1), releasedAt = ?1 where nodeId = ?2 and ownerId in ?3 and releasedAt is null",
                now, nodeId, it,
            )
        }
    }

    /**
     * The blobs [ids] are released by name as of [now] (Expenses: a revision's `droppedReceipts`), for good: no keep
     * reaches them again. One released by name before stays released at its first time; one released with its owner's
     * end is released again now. As [keep], in the application's write; the caller checked each is its owner's ([lock]).
     */
    fun release(ids: Collection<String>, now: Instant) {
        ids.distinct().sorted().chunked(CHUNK).forEach {
            ApplicationBlobEntity.update("releasedAt = ?1 where id in ?2 and (releasedAt is null or keptAt is not null)", now, it)
            ApplicationBlobEntity.update("keptAt = null where id in ?1", it)
        }
    }

    /** Whether any of [ids] is an application blob's id — an application refuses such an id for a record of its own,
     *  since both are reportable items' ids. */
    fun anyTaken(ids: Collection<String>): Boolean =
        ids.distinct().chunked(CHUNK).any { ApplicationBlobEntity.count("id in ?1", it) > 0 }

    /** Every blob row of the blobs the owner [ownerId] holds — released or not: what a report of the application's own
     *  record blocks with it, and hands over the wraps of. */
    fun blobIdsOfOwner(ownerId: String): Set<String> =
        ApplicationBlobEntity.list("ownerId", ownerId).mapTo(LinkedHashSet()) { it.blobId }

    /** The stored objects of the blobs the owner [ownerId] held, as the hold [held] copied them. */
    fun heldObjectsOfOwner(ownerId: String, held: List<HeldRow>): Set<String> = heldObjects(held) { it["owner_id"] == ownerId }

    // --- reads ---------------------------------------------------------------------------------------------------

    /** Every blob of the node [nodeId], in id order as text (a UUID column does not sort as its text). */
    fun allOf(nodeId: String): List<ApplicationBlobEntity> = ApplicationBlobEntity.list("nodeId", nodeId).sortedBy { it.id }

    /** Every blob of the node [nodeId] of [nodeKind] in a role that is an item of its own, in id order as text: what the
     *  service's own node and link reads serve (an owner's content is its application's to serve, ADR 0113). */
    fun itemsOf(nodeId: String, nodeKind: String): List<ApplicationBlobEntity> = allOf(nodeId).filter { isOwnItem(nodeKind, it.role) }

    /** Every blob the owners [ownerIds] hold — released or not — in no promised order (an application's record read
     *  groups them by owner). One query for a page of records (chunked at the page cap, `MediaListQueryCountSliceTest`). */
    fun ofOwners(ownerIds: Collection<String>): List<ApplicationBlobEntity> =
        ownerIds.distinct().chunked(ItemsPage.CAP).flatMap { ApplicationBlobEntity.list("ownerId in ?1", it) }

    /**
     * One page of the node's blobs whose `K_blob` is wrapped under the node's key, past [after] by blob id, at most
     * [limit] (ADR 0113 decision 7): every row but one a `WRITE_ONLY` guest wrote, which is sealed under its own `K_up`
     * and left as it is (ADR 0109 point 5). Each with the id its wrap is bound to.
     */
    fun rekeyPage(scopeId: String, nodeId: String, nodeKind: String, after: String?, limit: Int): ItemsPage<ApplicationBlobRekeyRow> {
        val query = if (after == null) {
            ApplicationBlobEntity.find("$UNDER_NODE_KEY order by a.blobId", scopeId, nodeId)
        } else {
            ApplicationBlobEntity.find("$UNDER_NODE_KEY and a.blobId > ?3 order by a.blobId", scopeId, nodeId, after)
        }
        val page = ItemsPage.of(query.range(0, limit).list(), limit) // range is inclusive: limit + 1 rows
        val wraps = page.items.map { it.blobId }.chunked(CHUNK).flatMap { BlobEntity.list("id in ?1", it) }.associate { it.id to it.wrappedBlobKey }
        val rows = page.items.mapNotNull { r ->
            val rule = roleOf(nodeKind, r.role) ?: return@mapNotNull null
            wraps[r.blobId]?.let { ApplicationBlobRekeyRow(r.blobId, r.role, rule.wrapOwner(r.nodeId, r.ownerId), it) }
        }
        return ItemsPage(rows, page.more, page.items.lastOrNull()?.let { ItemsCursor(0, it.blobId) })
    }

    /** Every row of the node whose wrap is under the node's key ([rekeyPage]'s rule), in id order; [lock]: `FOR UPDATE`. */
    fun underNodeKey(scopeId: String, nodeId: String, lock: Boolean = false): List<ApplicationBlobEntity> =
        ApplicationBlobEntity.find("$UNDER_NODE_KEY order by a.id", scopeId, nodeId)
            .let { if (lock) it.withLock(LockModeType.PESSIMISTIC_WRITE) else it }.list()

    /**
     * [rows] as a read serves them: each with its blob row, its derived purge time under [retention], and whether a
     * content block in [view] reaches it — itself, its owner or its node. A row whose blob row is gone is left out. A
     * constant number of queries per call.
     */
    fun served(rows: List<ApplicationBlobEntity>, retention: Duration, view: ContentView): List<ApplicationBlobRow> {
        if (rows.isEmpty()) return emptyList()
        val blobRows = rows.map { it.blobId }.chunked(CHUNK).flatMap { BlobEntity.list("id in ?1", it) }.associateBy { it.id }
        return rows.mapNotNull { r ->
            val blob = blobRows[r.blobId] ?: return@mapNotNull null
            val blocked = view.itemBlocked(r.id, r.nodeId) || r.ownerId in view.itemIds
            ApplicationBlobRow(r, blob, blobs.decodeManifest(blob.manifest), ApplicationBlobEntity.purgeAfter(r, retention), blocked)
        }
    }

    companion object {
        const val CHUNK = 500

        /** The table a hold copies the rows into, as stored (lower case). */
        const val TABLE = "veyq_application_blob"

        const val STALE_EPOCH = "stale_epoch"

        /** A node's rows whose `K_blob` is wrapped under its key: not one a link identity with a wrapped key wrote (a
         *  `WRITE_ONLY` guest's, under its `K_up`) — `MediaRekeyRows`' rule before ADR 0113, core's since it owns the drop key. */
        private const val UNDER_NODE_KEY =
            "from ApplicationBlobEntity a where a.scopeId = ?1 and a.nodeId = ?2 and not exists (" +
                "select i.id from ShareIdentityEntity i where i.id = a.writerIdentityId and i.wrappedIdentityKey is not null)"
        const val NODE_REKEY_PENDING = "node_rekey_pending"

        /** The storage keys of the held rows [held] picks of this table, through their held blob rows. */
        fun heldObjects(held: List<HeldRow>, pick: (HeldRow) -> Boolean): Set<String> {
            val blobIds = held.filter { it.table == TABLE && pick(it) }.mapNotNull { it["blob_id"] }
            if (blobIds.isEmpty()) return emptySet()
            return held.filter { it.table == "veyq_blob" && it.sourceId in blobIds }.mapNotNullTo(LinkedHashSet()) { it["storage_key"] }
        }
    }
}
