package me.raddatz.veyq.core.node.rekey

import me.raddatz.veyq.core.membership.AccessMode
import me.raddatz.veyq.core.membership.AccessMode.READ
import me.raddatz.veyq.core.membership.AccessMode.WRITE
import me.raddatz.veyq.core.scope.ScopeEntity
import jakarta.enterprise.context.ApplicationScoped

/**
 * A rotation's scope-level move before its commit (ADR 0115 decision 2): the rotator reads every value key's wrap and
 * scope-root entry under the scope key page by page ([parts]), re-wraps each key (re-seals each root entry) under the
 * key it minted and stages it ([stage] — core's own and each application's, under its part); `rotateScope` then swaps
 * every staged wrap in or nothing ([ScopeRekeyCommit.commit]), which is where a row written since its read is refused.
 * A rotation re-wraps keys and never rewrites a value. Every operation asks [RekeyAccess.checkRotator] first. Nothing
 * here writes a row a member reads.
 */
@ApplicationScoped
class ScopeRekeyHandler(private val rights: RekeyAccess, private val rows: ScopeRekeyCommit, private val staged: ScopeRekeyStage) {

    /** The caller's right to read ([READ]) or stage ([WRITE]) the scope's re-seal as a refusal, or null when granted —
     *  an application's listing and stage ask it too. */
    fun refusal(userId: String, scopeId: String, mode: AccessMode): RekeyOutcome? =
        rights.checkRotator(userId, scopeId, mode) as? RekeyOutcome

    /** One page after [since] (the cursor the last page answered, null for the first), at most [limit] rows. */
    fun parts(userId: String, scopeId: String, since: String?, limit: Int): RekeyOutcome {
        refusal(userId, scopeId, READ)?.let { return it }
        val cursor = since?.takeIf { it.isNotEmpty() }?.let { ScopeRekeyCommit.Cursor.parse(it) ?: return RekeyOutcome.InvalidRequest }
        val scope = ScopeEntity.findById(scopeId) ?: return RekeyRight.NoScope
        return RekeyOutcome.ScopeParts(rows.parts(scope, cursor, limit))
    }

    /**
     * Re-wrapped value keys and re-sealed root entries of [part] (core's, [ScopeRekeyStage.CORE], or an application's,
     * [ScopeRekeyParticipant.part]), each beside the wrap it replaces or the revision it was sealed against, staged under
     * [rekeyId] while the scope is still at [fromEpoch]: a rotation committed since is [RekeyOutcome.Stale] — the rotator
     * rebuilds from a fresh key. Nothing is compared here: the commit compares every staged value with its row.
     */
    fun stage(
        userId: String, scopeId: String, rekeyId: String, fromEpoch: Int, part: String,
        values: Map<ScopeRekeyStage.Key, ScopeRekeyStage.Value>,
    ): RekeyOutcome {
        refusal(userId, scopeId, WRITE)?.let { return it }
        val current = ScopeEntity.findById(scopeId)?.currentEpoch ?: return RekeyRight.NoScope
        if (current != fromEpoch) return RekeyOutcome.Stale
        staged.put(rekeyId, scopeId, part, values)
        return RekeyOutcome.Staged
    }
}
