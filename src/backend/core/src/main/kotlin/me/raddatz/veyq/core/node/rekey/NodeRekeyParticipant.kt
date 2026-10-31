package me.raddatz.veyq.core.node.rekey

/**
 * An application's part of a node's re-key (ADR 0109 point 2): the fields it stores sealed under a node's
 * key — Memories' media meta, EXIF, original and derivative `K_blob` wraps and bucket names; an Expenses Pot's
 * revision-key and receipt `K_blob` wraps. Core owns the commit's
 * transaction and its order and calls every participant inside it; core names no application's table (ADR 0079).
 *
 * **Fail closed.** [commit] runs after core has locked the scope and the node and checked its own parts. It first
 * decides, without writing, whether every row of its own that is sealed under [nodeId]'s key has a value in [staged]
 * ([Verdict.MISSING] if not) and whether each value was staged against the revision the row still has
 * ([Verdict.STALE] if not); only then does it write, and nothing it does may fail after a write. Core rolls the whole
 * transaction back on any verdict but [Verdict.APPLIED], so a participant that refuses after another applied leaves
 * nothing behind. A row a WRITE_ONLY guest sealed under its own `K_up` is not the node key's and is neither required nor
 * written. A participant that bumps its own change counter (a gallery's `items.bump`) does it here, after its rows.
 *
 * `scripts/validate_node_rekey_arity.py` derives every column sealed under a node key from the entities and is red on
 * one neither core's commit nor a participant rewrites.
 */
interface NodeRekeyParticipant {
    /** The stage part this participant's values are kept under ([NodeRekeyStage]) — its application's name. */
    val part: String

    /**
     * Lock this participant's rows of [nodeId] — called by core's commit after the scope's and the links' row locks and
     * BEFORE the node's, because an application's writers take their own rows before the node's (a gallery's media rows,
     * then the node: `NodeItemsRevisions.bump`, a medium's meta write ahead of the epoch gate). Locking them after the node
     * would invert that order. Rows locked here are the ones [commit] rewrites; a row inserted meanwhile needs the node's
     * key and so the node's lock, which the commit holds by then.
     */
    fun lockRows(scopeId: String, nodeId: String) {}

    fun commit(scopeId: String, nodeId: String, staged: Map<NodeRekeyStage.Key, NodeRekeyStage.Value>): Verdict

    enum class Verdict { APPLIED, MISSING, STALE }
}
