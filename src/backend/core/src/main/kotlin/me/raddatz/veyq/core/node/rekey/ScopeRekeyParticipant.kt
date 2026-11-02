package me.raddatz.veyq.core.node.rekey

/**
 * An application's part of a rotation's scope-level move (ADR 0115 decision 2): the value keys it stores wrapped under
 * the scope key itself, not under a node's — Memories' item marks (`item-favorites`, each row under a value key of its
 * own). **A rotation re-wraps keys and never rewrites a value:** a participant swaps in the re-wrapped wraps and never a
 * row's ciphertext. Core owns the rotation's transaction and its order and calls every participant inside it; core
 * names no application's table (ADR 0079 decision 3). The shape of [NodeRekeyParticipant], one level up.
 *
 * **Fail closed.** [commit] runs inside the rotation's commit (`ScopesStore.rotate`), after core has locked the scope,
 * applied a removal's deletes and decided its own rows. It first decides, without writing, whether [staged] holds a
 * field other than its own rows' wraps ([Verdict.WRITES_VALUE] — a write of a value), whether every row of its own that
 * holds a wrap under the scope key has a replacement in [staged] ([Verdict.MISSING] if not) and whether each was staged
 * against the wrap the row still holds ([ScopeRekeyStage.current], [Verdict.STALE] if not); only then does it write,
 * and nothing it does may fail after a write. Core rolls the whole rotation back on any verdict but [Verdict.APPLIED],
 * so a participant that refuses after another applied leaves nothing behind. A staged wrap for a row that no longer
 * exists (a removed member's, erased in the same transaction) is no refusal: there is nothing left under the old key.
 *
 * `scripts/validate_scope_rekey_arity.py` classifies every seal purpose and is red on a wrap column under the scope key
 * neither core's commit ([ScopeRekeyCommit]) nor a participant rewrites — and on a value column either assigns.
 */
interface ScopeRekeyParticipant {
    /** The stage part this participant's values are kept under ([ScopeRekeyStage]) — its application's name. */
    val part: String

    /**
     * Lock this participant's rows of [scopeId] — called by core's commit under the scope's row lock and before it
     * writes, in the order its own writers take their rows (each takes the scope row first, so a row lock here orders
     * against nothing but a writer that skipped it). Rows locked here are the ones [commit] rewrites.
     */
    fun lockRows(scopeId: String) {}

    fun commit(scopeId: String, rekeyId: String, newEpoch: Int, staged: Map<ScopeRekeyStage.Key, ScopeRekeyStage.Value>): Verdict

    enum class Verdict { APPLIED, MISSING, STALE, WRITES_VALUE }
}
