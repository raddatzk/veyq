package me.raddatz.veyq.expenses.pot

import me.raddatz.veyq.core.http.HttpFailure
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
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType

/** What `listPotRekey` answers: a refusal, or one page of the Pot's revision key wraps. */
sealed interface PotRekeyListOutcome {
    data class Page(val revisions: ItemsPage<PotRevisionEntity>) : PotRekeyListOutcome
    data class Refused(val failure: HttpFailure) : PotRekeyListOutcome
}

/** The Expenses part of a node's re-key: its stage part's name and the one field it stages. */
object PotRekeyRows {
    const val PART = "expenses"

    /** A revision's `K_rev` wrap, keyed by the revision's [RevisionRef.key] — the only field a re-key of a Pot writes. */
    const val WRAP = "revision.wrappedRevisionKey"
}

/**
 * The Expenses half of a Pot's re-key (ADR 0109 point 2, ADR 0108 decision 4): the listing a re-keyer reads — every
 * revision's `K_rev` wrap and nothing else — and the staging it writes through, under core's re-key rights
 * ([RekeyHandler]): an owner with the current grant, not excluded.
 */
@ApplicationScoped
class PotRekeyHandler(private val rekey: RekeyHandler, private val store: PotRevisionStore) {

    fun list(userId: String, scopeId: String, nodeId: String, after: RevisionRef?, limit: Int): PotRekeyListOutcome {
        rekey.refusal(userId, scopeId, READ)?.let { return PotRekeyListOutcome.Refused(it as HttpFailure) }
        potOf(scopeId, nodeId) ?: return PotRekeyListOutcome.Refused(RekeyOutcome.NotFound)
        return PotRekeyListOutcome.Page(store.pageByAddress(nodeId, after, limit))
    }

    /** Stage [wraps] (revision → its re-wrapped `K_rev`) under [rekeyId] for core's commit; core checks the rights and
     *  that the Pot still lags at [fromEpoch]. */
    fun stage(userId: String, scopeId: String, nodeId: String, rekeyId: String, fromEpoch: Int, wraps: Map<RevisionRef, String>): RekeyOutcome {
        rekey.refusal(userId, scopeId, WRITE)?.let { return it }
        potOf(scopeId, nodeId) ?: return RekeyOutcome.NotFound
        val values = wraps.entries.associate { (ref, wrap) -> Key(ref.key(), PotRekeyRows.WRAP) to Value(wrap, null) }
        return rekey.stagePart(userId, scopeId, nodeId, rekeyId, fromEpoch, PotRekeyRows.PART, values)
    }

    /** The Pot [nodeId] of [scopeId], live or trashed — a trashed Pot is re-keyed too, so a restore opens. */
    private fun potOf(scopeId: String, nodeId: String): NodeEntity? =
        NodeEntity.findById(nodeId)?.takeIf { it.scopeId == scopeId && it.kind == ExpensesNodeKinds.POT }
}

/**
 * Expenses' participant in core's commit of a node's re-key ([NodeRekeyParticipant]), run in that transaction for every
 * node — a node that is no Pot holds no revision and passes. Its rows are locked in [lockRows], after core's scope and
 * share locks and BEFORE the node's — the order every Pot writer takes ([PotRevisionStore]'s lock-order note). [commit]
 * **decides before it writes**:
 *
 * - every revision of the Pot has its `K_rev` wrap staged ([NodeRekeyParticipant.Verdict.MISSING] if not) — a revision
 *   appended since the listing included, which the re-keyer then stages and commits again;
 * - every staged value is a wrap of a revision the Pot still holds ([NodeRekeyParticipant.Verdict.STALE] if not): a
 *   revision erased since, and **anything that is not a wrap** — a re-key changes no body, so a part naming any other
 *   field lands nothing, the wraps beside it included (ADR 0108 decision 4: not even the owner who re-keys rewrites the
 *   history).
 *
 * Only then it writes the wraps, moves the Pot's item revision once and gives every revision that position, so an open
 * list's next poll re-reads them with their new wraps (the keyset delta carries a row only past the cursor). The body,
 * the writer and the time stay.
 */
@ApplicationScoped
class PotNodeRekeyParticipant(private val items: NodeItemsRevisions) : NodeRekeyParticipant {

    override val part: String = PotRekeyRows.PART

    override fun lockRows(scopeId: String, nodeId: String) {
        rows(scopeId, nodeId, lock = true)
    }

    override fun commit(scopeId: String, nodeId: String, staged: Map<Key, Value>): NodeRekeyParticipant.Verdict {
        // Held since [lockRows]: read, not locked again — a lock here, after the node's, would invert the order.
        val revisions = rows(scopeId, nodeId)
        val held = revisions.associateBy { RevisionRef(it.entryId, it.revision).key() }
        if (staged.keys.any { it.field != PotRekeyRows.WRAP || it.itemId !in held }) return NodeRekeyParticipant.Verdict.STALE
        if (held.keys.any { Key(it, PotRekeyRows.WRAP) !in staged }) return NodeRekeyParticipant.Verdict.MISSING

        // Write — nothing below can refuse.
        for ((key, row) in held) row.wrappedRevisionKey = staged.getValue(Key(key, PotRekeyRows.WRAP)).value
        if (revisions.isNotEmpty()) {
            val position = items.bump(scopeId, nodeId) // after the record rows: record rows -> node row -> revision row
            revisions.forEach { it.position = position }
        }
        return NodeRekeyParticipant.Verdict.APPLIED
    }

    private fun rows(scopeId: String, nodeId: String, lock: Boolean = false): List<PotRevisionEntity> =
        PotRevisionEntity.find("scopeId = ?1 and nodeId = ?2 order by entryId, revision", scopeId, nodeId)
            .let { if (lock) it.withLock(LockModeType.PESSIMISTIC_WRITE) else it }.list()
}
