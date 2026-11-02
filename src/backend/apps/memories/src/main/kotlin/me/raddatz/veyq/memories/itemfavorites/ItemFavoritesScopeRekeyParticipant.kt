package me.raddatz.veyq.memories.itemfavorites

import me.raddatz.veyq.core.node.rekey.ScopeRekeyParticipant
import me.raddatz.veyq.core.node.rekey.ScopeRekeyStage
import io.quarkus.panache.common.Sort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType

/**
 * Memories' part of a rotation's scope-level move (ADR 0115 decision 2): every member's item marks of the scope, on every
 * node, are sealed under a value key of their own whose wrap is under the scope key itself (`item-favorites`, ADR 0068 —
 * no node key), so the rotation's commit swaps in each row's re-wrapped key the rotator staged
 * (`stageScopeItemFavoritesRekey`, by its opaque [ItemFavoritesEntity.markId]) or refuses: a row with none is
 * [ScopeRekeyParticipant.Verdict.MISSING], one written since it was staged [ScopeRekeyParticipant.Verdict.STALE], and a
 * stage that writes anything but a wrap [ScopeRekeyParticipant.Verdict.WRITES_VALUE]. **Wraps only:** a row's ciphertext
 * — and the self-tag inside it — is never rewritten, so a rotator can make another member's marks unreadable, never
 * rewrite them, and whose a row is stays as hidden as the node's read keeps it.
 */
@ApplicationScoped
class ItemFavoritesScopeRekeyParticipant : ScopeRekeyParticipant {
    override val part = PART

    /** `SELECT … FOR UPDATE` on the scope's rows, in id order: a member's put takes the scope row first (the commit
     *  holds it), but an account's erasure deletes its rows by account, so this keeps the rows the commit judged. */
    override fun lockRows(scopeId: String) {
        ItemFavoritesEntity.find("scopeId", Sort.by("id"), scopeId).withLock(LockModeType.PESSIMISTIC_WRITE).list()
    }

    override fun commit(
        scopeId: String, rekeyId: String, newEpoch: Int, staged: Map<ScopeRekeyStage.Key, ScopeRekeyStage.Value>,
    ): ScopeRekeyParticipant.Verdict {
        // A rotation re-wraps keys and never rewrites a value: a staged field that is no row's wrap is refused first.
        if (staged.keys.any { it.field != FIELD }) return ScopeRekeyParticipant.Verdict.WRITES_VALUE
        val rows = ItemFavoritesEntity.list("scopeId", scopeId)
        val wraps = rows.map { row ->
            val v = staged[ScopeRekeyStage.Key(row.markId, FIELD)] ?: return ScopeRekeyParticipant.Verdict.MISSING
            if (!ScopeRekeyStage.current(v, row.wrappedValueKey)) return ScopeRekeyParticipant.Verdict.STALE
            row to v.value
        }
        for ((row, wrap) in wraps) row.wrappedValueKey = wrap
        return ScopeRekeyParticipant.Verdict.APPLIED
    }

    companion object {
        /** The stage part Memories' item marks are kept under. */
        const val PART = "memories"
        /** The one field Memories' stage holds: a row's value key re-wrapped. */
        const val FIELD = "mark.wrappedValueKey"
    }
}
