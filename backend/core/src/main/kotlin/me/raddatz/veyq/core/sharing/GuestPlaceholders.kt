package me.raddatz.veyq.core.sharing

/**
 * Which nodes are an **empty upload placeholder** a guest may take back (`abandonShareNode`, ADR 0080 decision 6): the
 * node a guest created for an upload that was then refused or given up — an upload into a full link, a cancel — and
 * that holds nothing. **The owner of the node's kind decides** what "holds nothing" means (Depot: a `FILE` whose slot
 * never held a blob); core checks the rest — the guest's reach, no child, no upload of anybody else open into it — and
 * erases. Core names no application kind (ADR 0079): the declaration is the application's, and core only reads it.
 *
 * Nodes store no authorship, so the server cannot tell whose placeholder it is: whatever an implementation declares,
 * any guest within reach may erase it. So it declares only a node nobody could lose anything with — never one that
 * holds, or ever held, content.
 *
 * **Exactly one implementation per kind, and none is the default** — the partition of [GuestNodeRules]. A kind no
 * implementation handles, or one two implementations claim, is never a placeholder (`not_a_placeholder`); so core
 * alone, and an application that declares nothing (Memories), let a guest erase nothing.
 */
interface GuestPlaceholders {
    /** Whether this implementation answers for nodes of [kind]. */
    fun handles(kind: String): Boolean

    /** Whether the node [nodeId] of [scopeId] — of a kind this implementation [handles] — holds nothing of its
     *  application's. Asked under the scope lock (`ShareNodesHandler.abandon`), so a completion either committed before
     *  it — and is seen — or waits for the erase and then finds no node. */
    fun isEmpty(scopeId: String, nodeId: String): Boolean

    companion object {
        /** The one implementation of [declared] that handles [kind] decides; none or several → not a placeholder. */
        fun isEmpty(declared: List<GuestPlaceholders>, kind: String, scopeId: String, nodeId: String): Boolean =
            declared.filter { it.handles(kind) }.singleOrNull()?.isEmpty(scopeId, nodeId) ?: false
    }
}
