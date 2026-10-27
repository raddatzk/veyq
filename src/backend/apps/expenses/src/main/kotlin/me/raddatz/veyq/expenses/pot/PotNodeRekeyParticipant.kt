package me.raddatz.veyq.expenses.pot

import me.raddatz.veyq.core.blob.BlobEntity
import me.raddatz.veyq.core.blob.StoredObjectLocks
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.items.ItemsCursor
import me.raddatz.veyq.core.items.ItemsPage
import me.raddatz.veyq.core.items.NodeItemsRevisions
import me.raddatz.veyq.core.membership.AccessMode.READ
import me.raddatz.veyq.core.membership.AccessMode.WRITE
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.node.rekey.NodeRekeyParticipant
import me.raddatz.veyq.core.node.rekey.NodeRekeyStage.Key
import me.raddatz.veyq.core.node.rekey.NodeRekeyStage.Value
import me.raddatz.veyq.core.node.rekey.RekeyHandler
import me.raddatz.veyq.core.node.rekey.RekeyOutcome
import me.raddatz.veyq.expenses.ExpensesNodeKinds
import me.raddatz.veyq.expenses.receipt.PotReceiptEntity
import me.raddatz.veyq.expenses.receipt.PotReceiptStore
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType

/** What `listPotRekey` answers: a refusal, or one page of the Pot's revision key wraps, then its receipts' (with their
 *  blob rows, whose `K_blob` wrap is the field). */
sealed interface PotRekeyListOutcome {
    data class Page(
        val revisions: List<PotRevisionEntity>,
        val receipts: List<Pair<PotReceiptEntity, BlobEntity>>,
        val more: Boolean,
        val cursor: ItemsCursor?,
    ) : PotRekeyListOutcome

    data class Refused(val failure: HttpFailure) : PotRekeyListOutcome
}

/** The Expenses part of a node's re-key: its stage part's name and the two fields it stages. */
object PotRekeyRows {
    const val PART = "expenses"

    /** A revision's `K_rev` wrap, keyed by the revision's [RevisionRef.key]. */
    const val WRAP = "revision.wrappedRevisionKey"

    /** A receipt's `K_blob` wrap — its blob row's — keyed by the receipt id (ADR 0109 decision 3: re-wrapped, never
     *  re-encrypted). With [WRAP], the only fields a re-key of a Pot writes. */
    const val RECEIPT_WRAP = "receipt.wrappedBlobKey"

    /** The listing's cursor position of a revision and of a receipt: the revisions' pages first, then the receipts'. */
    const val REVISIONS: Long = 0
    const val RECEIPTS: Long = 1
}

/**
 * The Expenses half of a Pot's re-key (ADR 0109 point 2, ADR 0108 decision 4): the listing a re-keyer reads — every
 * revision's `K_rev` wrap and nothing else — and the staging it writes through, under core's re-key rights
 * ([RekeyHandler]): any member with the current grant, not excluded (an owner on an owner-only node).
 */
@ApplicationScoped
class PotRekeyHandler(private val rekey: RekeyHandler, private val store: PotRevisionStore, private val receipts: PotReceiptStore) {

    /**
     * One page past [since]: the revisions in (entry id, revision) order, then — once they are through — the receipts in
     * id order, at most [limit] of both together. A cursor at [PotRekeyRows.RECEIPTS] reads receipts only; any other
     * (or none) starts with the revisions past its revision.
     */
    fun list(userId: String, scopeId: String, nodeId: String, since: ItemsCursor?, limit: Int): PotRekeyListOutcome {
        rekey.refusal(userId, scopeId, READ, nodeId)?.let { return PotRekeyListOutcome.Refused(it as HttpFailure) }
        potOf(scopeId, nodeId) ?: return PotRekeyListOutcome.Refused(RekeyOutcome.NotFound)
        val inReceipts = since?.position == PotRekeyRows.RECEIPTS
        val revisions = if (inReceipts) ItemsPage(emptyList(), false) else store.pageByAddress(nodeId, since?.let { RevisionRef.parse(it.id) }, limit)
        if (revisions.more) return PotRekeyListOutcome.Page(revisions.items, emptyList(), true, revisions.cursor)
        val room = limit - revisions.items.size
        val after = if (inReceipts) since?.id else null
        val fetched = receipts.pageById(nodeId, after, room + 1) // one past the room only says more remain
        val shown = fetched.take(room)
        val blobRows = shown.map { it.blobId }.takeIf { it.isNotEmpty() }?.let { BlobEntity.list("id in ?1", it) }.orEmpty().associateBy { it.id }
        val cursor = shown.lastOrNull()?.let { ItemsCursor(PotRekeyRows.RECEIPTS, it.id) } ?: revisions.cursor
        return PotRekeyListOutcome.Page(revisions.items, shown.mapNotNull { r -> blobRows[r.blobId]?.let { r to it } }, fetched.size > room, cursor)
    }

    /** Stage [wraps] (revision → its re-wrapped `K_rev`) and [receiptWraps] (receipt id → its re-wrapped `K_blob`) under
     *  [rekeyId] for core's commit; core checks the rights and that the Pot still lags at [fromEpoch]. */
    fun stage(
        userId: String, scopeId: String, nodeId: String, rekeyId: String, fromEpoch: Int, wraps: Map<RevisionRef, String>,
        receiptWraps: Map<String, String>,
    ): RekeyOutcome {
        rekey.refusal(userId, scopeId, WRITE, nodeId)?.let { return it }
        potOf(scopeId, nodeId) ?: return RekeyOutcome.NotFound
        val values = wraps.entries.associate { (ref, wrap) -> Key(ref.key(), PotRekeyRows.WRAP) to Value(wrap, null) } +
            receiptWraps.entries.associate { (id, wrap) -> Key(id, PotRekeyRows.RECEIPT_WRAP) to Value(wrap, null) }
        return rekey.stagePart(userId, scopeId, nodeId, rekeyId, fromEpoch, PotRekeyRows.PART, values)
    }

    /** The Pot [nodeId] of [scopeId], live or trashed — a trashed Pot is re-keyed too, so a restore opens. */
    private fun potOf(scopeId: String, nodeId: String): NodeEntity? =
        NodeEntity.findById(nodeId)?.takeIf { it.scopeId == scopeId && it.kind == ExpensesNodeKinds.POT }
}

/**
 * Expenses' participant in core's commit of a node's re-key ([NodeRekeyParticipant]), run in that transaction for every
 * node — a node that is no Pot holds no revision and no receipt and passes. Its rows are locked in [lockRows] — the
 * revisions, then the receipts — after core's scope and share locks and BEFORE the node's, the order every Pot writer
 * takes ([PotRevisionStore]'s lock-order note); the receipts' blob rows after the node's ([StoredObjectLocks]: item rows
 * before blob rows), as Memories' participant locks its media's. [commit] **decides before it writes**:
 *
 * - every revision of the Pot has its `K_rev` wrap staged, and every receipt its `K_blob` wrap
 *   ([NodeRekeyParticipant.Verdict.MISSING] if not) — one appended or completed since the listing included, which the
 *   re-keyer then stages and commits again;
 * - every staged value is a wrap of a revision or a receipt the Pot still holds ([NodeRekeyParticipant.Verdict.STALE] if
 *   not): a revision erased or a receipt purged since, and **anything that is not a wrap** — a re-key changes no body
 *   and no blob, so a part naming any other field lands nothing, the wraps beside it included (ADR 0108 decision 4: not
 *   even the member who re-keys rewrites the history).
 *
 * Only then it writes the wraps (a receipt's into its blob row: `K_blob` is re-wrapped, never re-encrypted, ADR 0109
 * decision 3), moves the Pot's item revision once and gives every revision that position, so an open list's next poll
 * re-reads them with their new wraps (the keyset delta carries a row only past the cursor) and re-reads the receipts
 * (their digest changed with the wraps). The body, the blob, the writer and the time stay.
 */
@ApplicationScoped
class PotNodeRekeyParticipant(private val items: NodeItemsRevisions) : NodeRekeyParticipant {

    override val part: String = PotRekeyRows.PART

    override fun lockRows(scopeId: String, nodeId: String) {
        rows(scopeId, nodeId, lock = true)
        receiptRows(scopeId, nodeId, lock = true)
    }

    override fun commit(scopeId: String, nodeId: String, staged: Map<Key, Value>): NodeRekeyParticipant.Verdict {
        // Held since [lockRows]: read, not locked again — a lock here, after the node's, would invert the order.
        val revisions = rows(scopeId, nodeId)
        val receipts = receiptRows(scopeId, nodeId)
        val held = revisions.associateBy { RevisionRef(it.entryId, it.revision).key() }
        val heldReceipts = receipts.associateBy { it.id }
        val stale = staged.keys.any { k ->
            when (k.field) {
                PotRekeyRows.WRAP -> k.itemId !in held
                PotRekeyRows.RECEIPT_WRAP -> k.itemId !in heldReceipts
                else -> true
            }
        }
        if (stale) return NodeRekeyParticipant.Verdict.STALE
        if (held.keys.any { Key(it, PotRekeyRows.WRAP) !in staged }) return NodeRekeyParticipant.Verdict.MISSING
        if (heldReceipts.keys.any { Key(it, PotRekeyRows.RECEIPT_WRAP) !in staged }) return NodeRekeyParticipant.Verdict.MISSING
        // The blob rows the receipts' wraps live in, after the node's lock (item rows before blob rows).
        val blobs = StoredObjectLocks.lockOf(scopeId, receipts.map { it.blobId }).associateBy { it.id }

        // Write — nothing below can refuse.
        for ((key, row) in held) row.wrappedRevisionKey = staged.getValue(Key(key, PotRekeyRows.WRAP)).value
        for (r in receipts) blobs[r.blobId]?.wrappedBlobKey = staged.getValue(Key(r.id, PotRekeyRows.RECEIPT_WRAP)).value
        if (revisions.isNotEmpty() || receipts.isNotEmpty()) {
            val position = items.bump(scopeId, nodeId) // after the record rows: record rows -> node row -> revision row
            revisions.forEach { it.position = position }
        }
        return NodeRekeyParticipant.Verdict.APPLIED
    }

    private fun receiptRows(scopeId: String, nodeId: String, lock: Boolean = false): List<PotReceiptEntity> =
        PotReceiptEntity.find("scopeId = ?1 and nodeId = ?2 order by id", scopeId, nodeId)
            .let { if (lock) it.withLock(LockModeType.PESSIMISTIC_WRITE) else it }.list()

    private fun rows(scopeId: String, nodeId: String, lock: Boolean = false): List<PotRevisionEntity> =
        PotRevisionEntity.find("scopeId = ?1 and nodeId = ?2 order by entryId, revision", scopeId, nodeId)
            .let { if (lock) it.withLock(LockModeType.PESSIMISTIC_WRITE) else it }.list()
}
