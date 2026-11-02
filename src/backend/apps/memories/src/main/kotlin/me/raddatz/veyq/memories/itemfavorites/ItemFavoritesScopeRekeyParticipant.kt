package me.raddatz.veyq.memories.itemfavorites

import me.raddatz.veyq.core.node.rekey.ScopeRekeyParticipant
import me.raddatz.veyq.core.node.rekey.ScopeRekeyStage
import io.quarkus.panache.common.Sort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType

/**
 * Memories' part of a rotation's scope-level re-seal (ADR 0115 decision 2): every member's item marks of the scope, on
 * every node, are sealed under the scope key itself (`item-favorites`, ADR 0068 — no node key), so the rotation's commit
 * swaps in each row's value the rotator staged (`stageScopeItemFavoritesRekey`, by its opaque [ItemFavoritesEntity.markId])
 * or refuses: a row with none is [ScopeRekeyParticipant.Verdict.MISSING], one written since it was staged
 * [ScopeRekeyParticipant.Verdict.STALE]. The self-tag inside each row is unchanged — the rotator opened and re-sealed
 * the same plaintext — so whose a row is stays as hidden as the node's read keeps it.
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
        val rows = ItemFavoritesEntity.list("scopeId", scopeId)
        val values = rows.map { row ->
            val v = staged[ScopeRekeyStage.Key(row.markId, FIELD)] ?: return ScopeRekeyParticipant.Verdict.MISSING
            if (!ScopeRekeyStage.current(v, row.ciphertext)) return ScopeRekeyParticipant.Verdict.STALE
            row to v.value
        }
        for ((row, ciphertext) in values) row.ciphertext = ciphertext
        return ScopeRekeyParticipant.Verdict.APPLIED
    }

    companion object {
        /** The stage part Memories' item marks are kept under. */
        const val PART = "memories"
        const val FIELD = "mark.ciphertext"
    }
}
