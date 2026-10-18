package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.moderation.block.BlockKind
import me.raddatz.veyq.core.moderation.block.MeasureTargets
import me.raddatz.veyq.core.scope.ScopeEraser
import jakarta.enterprise.context.ApplicationScoped

/**
 * A group's links (ADR 0029 §6) go with it: erasing rather than retaining is the operator-blind answer, not a
 * concession to the foreign key — the row holds the owner, the node and a sealed name of a group whose erasure
 * its owner asked for, and with the scope gone there is no capability left for it to revoke. Phase 800.
 *
 * Inside, the order is the constraint: each id is RETIRED first (ADR 0029 §7) — a freed id could be minted again
 * by anyone holding the dead link's id and key, and every other holder of that link would open the minter's
 * node — then `veyq_share_identity` (FK to `veyq_share`, no `scope_id`, so by share ids), then the shares.
 */
@ApplicationScoped
class ShareScopeEraser(private val measures: MeasureTargets) : ScopeEraser {
    override val phase = 800

    override fun eraseScope(scopeId: String) {
        val shareIds = ShareEntity.find("scopeId", scopeId).list().map { it.id }
        val identityIds = if (shareIds.isEmpty()) emptyList() else ShareIdentityEntity.list("shareId in ?1", shareIds).map { it.id }
        shareIds.forEach { id -> ShareRetiredIdEntity().apply { this.id = id }.persist() }
        if (shareIds.isNotEmpty()) ShareIdentityEntity.delete("shareId in ?1", shareIds)
        ShareEntity.delete("scopeId", scopeId)
        // A measure on a link or a guest uploader ends with it (ADR 0093 § 6): after the deletes, which hold the rows' locks.
        measures.erased(MeasureTargets.ON_LINK, shareIds)
        measures.erased(listOf(BlockKind.GUEST_IDENTITY), identityIds)
    }
}
