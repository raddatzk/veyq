package me.raddatz.veyq.core.node.rekey

import me.raddatz.veyq.core.membership.AccessMode
import me.raddatz.veyq.core.membership.AccessMode.READ
import me.raddatz.veyq.core.membership.AccessMode.WRITE
import me.raddatz.veyq.core.scope.ScopeEntity
import jakarta.enterprise.context.ApplicationScoped

/**
 * A rotation's scope-level move before its commit (ADR 0115 decision 2): the rotator reads every value key's wrap and
 * scope-root entry under the scope key page by page ([parts]), re-wraps each key (re-seals each root entry) under the
 * key it minted and stages it ([stage]; an application its own wraps through [stagePart]); `rotateScope` then swaps
 * every staged wrap in or nothing ([ScopeRekeyCommit.commit]). A rotation re-wraps keys and never rewrites a value. Every
 * operation asks [RekeyAccess.checkRotator] first. Nothing here writes a row a member reads.
 */
@ApplicationScoped
class ScopeRekeyHandler(private val rights: RekeyAccess, private val rows: ScopeRekeyCommit, private val stage: ScopeRekeyStage) {

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

    /** Core's own re-wrapped value keys and re-sealed root entries, staged under [rekeyId] while the scope is still at
     *  [fromEpoch]. */
    fun stage(userId: String, scopeId: String, rekeyId: String, fromEpoch: Int, fields: List<ScopeRekeyField>): RekeyOutcome {
        refusal(userId, scopeId, WRITE)?.let { return it }
        val scope = ScopeEntity.findById(scopeId) ?: return RekeyRight.NoScope
        if (scope.currentEpoch != fromEpoch) return RekeyOutcome.Stale
        // A row written since the rotator read it (ADR 0115 decision 2): nothing of the batch is staged.
        if (!rows.stage(rekeyId, scope, fields)) return RekeyOutcome.PartsStale
        return RekeyOutcome.Staged
    }

    /**
     * An application's re-wrapped value keys ([ScopeRekeyParticipant.part]), each beside the digest of what its row holds now
     * (the application reads its own rows, [ScopeRekeyStage.digest], and refuses a value whose stated digest is not that
     * one before it calls this — ADR 0115 decision 2), staged under [rekeyId] while the scope is still at [fromEpoch]: a
     * rotation committed since is [RekeyOutcome.Stale] — the rotator rebuilds from a fresh key.
     */
    fun stagePart(
        userId: String, scopeId: String, rekeyId: String, fromEpoch: Int, part: String,
        values: Map<ScopeRekeyStage.Key, ScopeRekeyStage.Value>,
    ): RekeyOutcome {
        refusal(userId, scopeId, WRITE)?.let { return it }
        val current = ScopeEntity.findById(scopeId)?.currentEpoch ?: return RekeyRight.NoScope
        if (current != fromEpoch) return RekeyOutcome.Stale
        stage.put(rekeyId, scopeId, part, values)
        return RekeyOutcome.Staged
    }
}
