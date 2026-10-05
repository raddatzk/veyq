package com.veyq.core.node

import jakarta.enterprise.context.ApplicationScoped

/** What a parent slot resolves to: the scope root, an existing node's kind, or nothing (foreign/missing). */
sealed interface ParentSlot {
    object Root : ParentSlot
    data class Node(val kind: String) : ParentSlot
    object Missing : ParentSlot
}

/**
 * Where a node of a kind may live in the tree (ADR 0079): the placement rule of the kinds one owner answers for. Core
 * owns the tree and knows `FOLDER` only ([FolderNodeRules]); an application answers for its own kinds (Memories:
 * `MEMORY`, `CHAPTER`). `NodesHandler` asks it on a create and on a move, after a missing or trashed parent has
 * already been refused — [parentAllows] never sees [ParentSlot.Missing] from there, and answers false if it does.
 *
 * **Exactly one implementation per kind, and none is the default.** A kind that no implementation [handles] is
 * placed nowhere (`illegal_structure`), and so is a kind two implementations claim, rather than letting CDI bean
 * order choose — the partition `NodeKindRulesPartitionSliceTest` pins. An empty collection therefore refuses every
 * kind, `FOLDER` included: fail closed, never open.
 */
interface NodeKindRules {
    /** Whether this implementation answers for nodes of [kind]. */
    fun handles(kind: String): Boolean

    /** Whether a node of [kind] — one this implementation [handles] — may live in [parent]. */
    fun parentAllows(kind: String, parent: ParentSlot): Boolean

    companion object {
        /** The one rule of [rules] that handles [kind] decides; none or several → false. */
        fun parentAllows(rules: List<NodeKindRules>, kind: String, parent: ParentSlot): Boolean =
            rules.filter { it.handles(kind) }.singleOrNull()?.parentAllows(kind, parent) ?: false
    }
}

/** Core's own kind: a `FOLDER` organizes only, and lives at the root or under another `FOLDER`. */
@ApplicationScoped
class FolderNodeRules : NodeKindRules {
    override fun handles(kind: String): Boolean = kind == NodeEntity.FOLDER

    override fun parentAllows(kind: String, parent: ParentSlot): Boolean =
        parent is ParentSlot.Root || (parent is ParentSlot.Node && parent.kind == NodeEntity.FOLDER)
}
