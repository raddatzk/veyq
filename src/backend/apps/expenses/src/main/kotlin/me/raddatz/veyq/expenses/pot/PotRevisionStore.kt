package me.raddatz.veyq.expenses.pot

import me.raddatz.veyq.core.applicationblob.ApplicationBlobRef
import me.raddatz.veyq.core.applicationblob.ApplicationBlobs
import me.raddatz.veyq.core.backup.ErasureLedger
import me.raddatz.veyq.core.identity.PrincipalKey
import me.raddatz.veyq.core.items.ItemsCursor
import me.raddatz.veyq.core.items.ItemsPage
import me.raddatz.veyq.core.items.NodeItemsRevisions
import me.raddatz.veyq.core.moderation.guestwrite.GuestWriteRecords
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.node.rekey.NodeEpochGate
import me.raddatz.veyq.expenses.ExpensesNodeKinds
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.UUID

/** The four lengths a sealed body may have (ADR 0108 decision 4): one per size class of padded plaintext. */
object PotBodies {
    /** Base64 length of the sealed body → its padded plaintext size class in bytes. */
    private val CLASSES = mapOf(744 to 512, 2792 to 2048, 10984 to 8192, 21908 to 16384)

    /** The size class of a sealed body of [base64Length] characters, or null for a length no class has (`400`). */
    fun sizeClassOf(base64Length: Int): Int? = CLASSES[base64Length]
}

/** One revision as its writer sent it — the member's and the guest's batch alike; [droppedReceipts] are the receipts of
 *  its record it no longer holds (ADR 0108 decision 7), which core's blob service releases (ADR 0112 decision 2). */
data class RevisionWrite(
    val entryId: String, val revision: Long, val tombstone: Boolean, val sealedBody: String, val wrappedRevisionKey: String,
    val droppedReceipts: List<String> = emptyList(),
)

/** A revision by its address. */
data class RevisionRef(val entryId: String, val revision: Long) {
    /** The id a list's cursor and id set carry for it: `entryId:revision`. */
    fun key(): String = "$entryId:$revision"

    companion object {
        private val UUID_SHAPE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        /** The [key] form back, or null when it is not one — a client's echo is never bound to a UUID column unchecked. */
        fun parse(raw: String): RevisionRef? {
            val colon = raw.lastIndexOf(':')
            if (colon <= 0) return null
            val entry = raw.substring(0, colon)
            val revision = raw.substring(colon + 1).toLongOrNull()?.takeIf { it >= 1 } ?: return null
            return if (UUID_SHAPE.matches(entry)) RevisionRef(entry.lowercase(), revision) else null
        }
    }
}

/** What a write of revisions did. Nothing is stored on any answer but [Stored]. */
sealed interface PotWriteResult {
    /** Every revision landed; [rowIds] are their rows' ids, in the batch's order. */
    data class Stored(val rowIds: List<String>) : PotWriteResult
    /** These records' latest revisions are not the ones the write was built on (0: the record does not exist). */
    data class Stale(val stale: List<RevisionRef>) : PotWriteResult
    /** A new record's id belongs to another Pot, or is an application blob's (a receipt's). */
    object IdTaken : PotWriteResult
    /** A dropped receipt is no receipt of its record in this Pot (`400 invalid_request`). */
    object ReceiptNotHeld : PotWriteResult
    /** No live Pot of that id in the scope — or, for a guest, the link or the Pot's place in its subtree gone since. */
    object NotFound : PotWriteResult
    /** The stated epoch is not the Pot's (`stale_epoch`). */
    object StaleEpoch : PotWriteResult
    /** The Pot is not re-keyed since the last rotation (`node_rekey_pending`). */
    object RekeyPending : PotWriteResult
}

/**
 * A Pot's revisions (ADR 0108 decisions 3-6): appended, never overwritten, removed only by a member's erasure of a
 * participant ([erase]) or with the Pot itself ([PotNodeSubtreeEraser], [PotScopeEraser]). Every writer calls core's
 * `items.bump` after the rows it changed (`NodeItemsRevisionBumpsTest` holds the set), and the rows take its revision as
 * their position.
 *
 * **Lock order, every writer:** (1) a guest's re-check under the scope and share locks ([append]'s `admitted`); (2) the
 * named records' existing rows, `FOR UPDATE`; (2b) the rows of core's blob service the records hold and the revisions
 * drop ([ApplicationBlobs.lock]); (3) the Pot's node row ([NodeEpochGate.check]), which serializes every write of the Pot;
 * (4) the inserted rows, and the keep and release of the receipts ([ApplicationBlobs.keep]); (5) the item-revision row
 * ([NodeItemsRevisions.bump]). The re-key's commit takes (2) for every row of the Pot, then the service's rows, before (3)
 * ([PotNodeRekeyParticipant.lockRows]), and a receipt's completion and the service's purge the same order
 * ([ApplicationBlobs]).
 *
 * **What a write tells the blob service** (ADR 0112 decision 2), in its own transaction: a revision that is no tombstone
 * keeps its record's receipts (a restore keeps again what the tombstone released), a tombstone releases them with the
 * record, and `droppedReceipts` are released for good — each at the revision's own time.
 *
 * **The compare-and-set reads the records again after (3)** ([recordsNow]). What (2) returned is what was committed when
 * it ran: a writer of the same records that held them, and committed while (2) waited, inserted rows that a locking read
 * which waited does not return (measured on H2: the second of two appends of `a:2` read `a:1` only, passed, and met the
 * first's row on `ux_expenses_revision_entry` — a `500` where a replay gets `409 stale_revision`; and a new record has no
 * row to wait on at all). Every inserter holds (3), so a plain read under it sees every revision any earlier one committed
 * (READ COMMITTED, ADR 0078). `PotAppendRaceSliceTest` holds it.
 */
@ApplicationScoped
class PotRevisionStore(
    private val epochs: NodeEpochGate,
    private val items: NodeItemsRevisions,
    private val guestWriteRecords: GuestWriteRecords,
    private val ledger: ErasureLedger,
    private val receipts: ApplicationBlobs,
) {

    /**
     * Append [writes] — distinct records, the caller checked — to the Pot [nodeId] as [writer], all or none: each
     * revision `n + 1` only on its record's latest `n`. [admitted], a guest's re-check of its link and target, runs
     * first in the transaction; [stored] runs after the rows landed, in the same transaction (the guest-write record).
     */
    @Transactional
    fun append(
        scopeId: String, nodeId: String, nodeEpoch: Int, writes: List<RevisionWrite>, writer: PrincipalKey,
        admitted: (() -> Boolean)? = null, stored: (List<String>) -> Unit = {},
    ): PotWriteResult {
        if (admitted != null && !admitted()) return PotWriteResult.NotFound
        lockRecords(writes.map { it.entryId })
        val held = receipts.lock(writes.map { it.entryId }, writes.flatMap { it.droppedReceipts })
        lockLivePot(scopeId, nodeId, nodeEpoch)?.let { return it }
        val records = recordsNow(writes.map { it.entryId })
        if (records.any { it.nodeId != nodeId }) return PotWriteResult.IdTaken
        val latest = records.groupBy { it.entryId }.mapValues { (_, rows) -> rows.maxOf { it.revision } }
        // A new record's id may not be a receipt's: both are a reportable item's id (`PotReportableItems.locate`).
        val created = writes.map { it.entryId }.filter { it !in latest }
        if (created.isNotEmpty() && receipts.anyTaken(created)) return PotWriteResult.IdTaken
        val stale = writes.filter { (latest[it.entryId] ?: 0L) != it.revision - 1 }
            .map { RevisionRef(it.entryId, latest[it.entryId] ?: 0L) }
        if (stale.isNotEmpty()) return PotWriteResult.Stale(stale)
        // Each dropped receipt is one of its record's in this Pot (read under the locks above: a receipt is never moved).
        if (!droppedHeld(writes, held, nodeId)) return PotWriteResult.ReceiptNotHeld

        val now = Instant.now()
        receipts.keep(nodeId, writes.filter { !it.tombstone }.map { it.entryId }, now)
        receipts.releaseOwners(nodeId, writes.filter { it.tombstone }.map { it.entryId }, now)
        receipts.release(writes.flatMap { it.droppedReceipts }, now) // a drop stays at its first time
        val rows = writes.map { w -> newRow(scopeId, nodeId, w.entryId, w.revision, w.tombstone, false, w.sealedBody, w.wrappedRevisionKey, writer, now) }
        rows.forEach { it.persist() }
        val position = items.bump(scopeId, nodeId) // after the record rows: record rows -> node row -> revision row
        rows.forEach { it.position = position }
        val ids = rows.map { it.id }
        stored(ids)
        return PotWriteResult.Stored(ids)
    }

    /**
     * A member's erasure of the record [entryId] (ADR 0108 decisions 3 and 6): its erasure revision `n + 1` on its
     * latest `n` lands and every earlier revision of the record is deleted, in one transaction — with the guest-write
     * records that name them, and a ledger line so a restored backup erases them again (ADR 0056 decision 8). A record
     * that is no record of this Pot is [PotWriteResult.NotFound].
     */
    @Transactional
    fun erase(scopeId: String, nodeId: String, nodeEpoch: Int, write: RevisionWrite, member: PrincipalKey.User): PotWriteResult {
        lockRecords(listOf(write.entryId))
        receipts.lock(listOf(write.entryId))
        lockLivePot(scopeId, nodeId, nodeEpoch)?.let { return it }
        val held = recordsNow(listOf(write.entryId))
        if (held.isEmpty() || held.any { it.nodeId != nodeId }) return PotWriteResult.NotFound
        val latest = held.maxOf { it.revision }
        if (latest != write.revision - 1) return PotWriteResult.Stale(listOf(RevisionRef(write.entryId, latest)))

        val earlier = held.map { it.id }
        guestWriteRecords.forget(earlier) // a guest's write record names the revision: it goes with it
        earlier.chunked(CHUNK).forEach { PotRevisionEntity.delete("id in ?1", it) }
        val now = Instant.now()
        receipts.keep(nodeId, listOf(write.entryId), now) // the erasure revision is the record's latest, and no tombstone
        val row = newRow(scopeId, nodeId, write.entryId, write.revision, false, true, write.sealedBody, write.wrappedRevisionKey, member, now)
        row.persist()
        row.position = items.bump(scopeId, nodeId, deleted = true) // a removal: both counters move, open lists drop the rows
        ledger.erasedByApplication(ExpensesErasures.Kind.EXPENSES_RECORD.name, RevisionRef(write.entryId, write.revision).key(), scopeId)
        return PotWriteResult.Stored(listOf(row.id))
    }

    /** Every row of [entryIds], `FOR UPDATE`, in one order. */
    private fun lockRecords(entryIds: List<String>) {
        entryIds.distinct().sorted().chunked(CHUNK).forEach {
            PotRevisionEntity.find("entryId in ?1 order by entryId, revision", it).withLock(LockModeType.PESSIMISTIC_WRITE).list()
        }
    }

    /** Whether every receipt a write drops is a receipt of its own record in this Pot, as [held] locked them. */
    private fun droppedHeld(writes: List<RevisionWrite>, held: List<ApplicationBlobRef>, nodeId: String): Boolean {
        val byId = held.associateBy { it.id }
        return writes.all { w -> w.droppedReceipts.all { id -> byId[id]?.let { it.nodeId == nodeId && it.ownerId == w.entryId } ?: false } }
    }

    /** Every row of [entryIds] as committed now — read under the node row's lock, which every inserter holds. */
    private fun recordsNow(entryIds: List<String>): List<PotRevisionEntity> =
        entryIds.distinct().sorted().chunked(CHUNK).flatMap {
            PotRevisionEntity.find("entryId in ?1 order by entryId, revision", it).list()
        }

    /** The Pot's node row locked and judged ([NodeEpochGate]); null when it is a live Pot of the scope at [stated]. */
    private fun lockLivePot(scopeId: String, nodeId: String, stated: Int): PotWriteResult? {
        when (epochs.check(scopeId, nodeId, stated)) {
            NodeEpochGate.Verdict.MISSING -> return PotWriteResult.NotFound
            NodeEpochGate.Verdict.STALE -> return PotWriteResult.StaleEpoch
            NodeEpochGate.Verdict.PENDING -> return PotWriteResult.RekeyPending
            NodeEpochGate.Verdict.OK -> Unit
        }
        // Refreshed under the lock by the gate: a trash that committed before it is seen here.
        val node = NodeEntity.findById(nodeId) ?: return PotWriteResult.NotFound
        return if (node.kind != ExpensesNodeKinds.POT || node.deletedAt != null) PotWriteResult.NotFound else null
    }

    private fun newRow(
        scopeId: String, nodeId: String, entryId: String, revision: Long, tombstone: Boolean, erasure: Boolean,
        sealedBody: String, wrap: String, writer: PrincipalKey, now: Instant,
    ) = PotRevisionEntity().apply {
        id = UUID.randomUUID().toString()
        this.scopeId = scopeId
        this.nodeId = nodeId
        this.entryId = entryId
        this.revision = revision
        this.tombstone = tombstone
        this.erasure = erasure
        this.sealedBody = sealedBody
        wrappedRevisionKey = wrap
        sizeClass = checkNotNull(PotBodies.sizeClassOf(sealedBody.length)) { "a body of no size class reached the store" }
        writerUserId = writer.userId
        writerIdentityId = writer.identityId
        createdAt = now
    }

    // --- reads ---------------------------------------------------------------------------------------------------

    /**
     * One page of the Pot's revisions past [since] in list order — position, then entry id, then revision (ADR 0033
     * decisions 3 and 6) — at most [limit]. The cursor's id is a [RevisionRef.key]; an id that is none reads from the
     * beginning, as an absent cursor does.
     */
    fun page(nodeId: String, since: ItemsCursor?, limit: Int): ItemsPage<PotRevisionEntity> {
        val after = since?.let { c -> RevisionRef.parse(c.id)?.let { c to it } }
        val query = if (after == null) {
            PotRevisionEntity.find("nodeId = ?1 order by position, entryId, revision", nodeId)
        } else {
            val (c, ref) = after
            PotRevisionEntity.find(
                "nodeId = ?1 and (position > ?2 or (position = ?2 and (entryId > ?3 or (entryId = ?3 and revision > ?4)))) " +
                    "order by position, entryId, revision",
                nodeId, c.position.toInt(), ref.entryId, ref.revision,
            )
        }
        val page = ItemsPage.of(query.range(0, limit).list(), limit) // range is inclusive: limit + 1 rows
        return ItemsPage(page.items, page.more, page.items.lastOrNull()?.let { ItemsCursor(it.position.toLong(), RevisionRef(it.entryId, it.revision).key()) })
    }

    /** Every revision the Pot holds now, by address ([RevisionRef.key]) — the id set after an erasure. */
    fun refs(nodeId: String): List<String> =
        PotRevisionEntity.getEntityManager()
            .createQuery("select r.entryId, r.revision from PotRevisionEntity r where r.nodeId = ?1", Array<Any>::class.java)
            .setParameter(1, nodeId).resultList
            .map { RevisionRef(it[0] as String, (it[1] as Number).toLong()).key() }

    /** Every revision of the Pot in the guest's order — entry id as text, then revision (a UUID column does not sort as
     *  its text, so the order is Kotlin's). */
    fun allInEntryOrder(nodeId: String): List<PotRevisionEntity> =
        PotRevisionEntity.list("nodeId", nodeId).sortedWith(compareBy({ it.entryId }, { it.revision }))

    /** One page of the Pot's revisions in (entry id, revision) order past [after] — the re-key's listing. */
    fun pageByAddress(nodeId: String, after: RevisionRef?, limit: Int): ItemsPage<PotRevisionEntity> {
        val all = allInEntryOrder(nodeId).let { rows ->
            if (after == null) rows else rows.dropWhile { ADDRESS_ORDER.compare(RevisionRef(it.entryId, it.revision), after) <= 0 }
        }
        val page = ItemsPage.of(all.take(limit + 1), limit)
        return ItemsPage(page.items, page.more, page.items.lastOrNull()?.let { ItemsCursor(0, RevisionRef(it.entryId, it.revision).key()) })
    }

    companion object {
        private const val CHUNK = 500

        private val ADDRESS_ORDER: Comparator<RevisionRef> = compareBy({ it.entryId }, { it.revision })
    }
}
