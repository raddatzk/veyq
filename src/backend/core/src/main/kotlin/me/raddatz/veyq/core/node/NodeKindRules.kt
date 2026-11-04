package me.raddatz.veyq.core.node

import jakarta.enterprise.context.ApplicationScoped

/** What a parent slot resolves to: the scope root, an existing node's kind, or nothing (foreign/missing). */
sealed interface ParentSlot {
    object Root : ParentSlot
    data class Node(val kind: String) : ParentSlot
    object Missing : ParentSlot
}

/**
 * Where a node of a kind may live in the tree (ADR 0079): the placement rule of the kinds one owner answers for. Core
 * owns the tree and knows `FOLDER` only ([FolderNodeRules]); an application answers for its own kinds.
 * `NodesHandler` asks it on a create and on a move, after a missing or trashed parent has
 * already been refused — [parentAllows] never sees [ParentSlot.Missing] from there, and answers false if it does.
 *
 * **Exactly one implementation per kind, and none is the default.** A kind that no implementation [handles] is
 * placed nowhere (`illegal_structure`), and so is a kind two implementations claim, rather than letting CDI bean
 * order choose — the partition `NodeKindRulesPartitionSliceTest` pins. An empty collection therefore refuses every
 * kind, `FOLDER` included: fail closed, never open.
 *
 * **What each answers is written down.** A kind's placement, cover, creation need and application — and its share roles
 * and guest rules (`ShareRoots`, `GuestNodeRules`, `GuestCreatableKinds`, `WriteOnlyGuestKinds`) — are stated in its
 * owner's `contracts/platform/kinds/<owner>.json`, which the web's registry answers too; the root's `KindsContractTest`
 * holds every implementation of these seams to it.
 */
interface NodeKindRules {
    /** Whether this implementation answers for nodes of [kind]. */
    fun handles(kind: String): Boolean

    /** Whether a node of [kind] — one this implementation [handles] — may live in [parent]. */
    fun parentAllows(kind: String, parent: ParentSlot): Boolean

    /**
     * Whether a node of [kind] — one this implementation [handles] — may carry a **cover**: an image attached to the
     * node itself, in its `COVER` slot ([me.raddatz.veyq.core.content.NodeCover], inbox #411). The kind's own answer,
     * so core names no application kind: `FOLDER` here, a drop box, a Memory, a Chapter and a Pot in their applications.
     * Default no — a kind opts in. Asked from the one rule that handles the kind, so a kind no rule claims takes none.
     */
    fun takesCover(kind: String): Boolean = false

    /**
     * The capability id (`contracts/platform/capabilities.json`) that creating a node of [kind] — one this
     * implementation [handles] — needs, or null for none, the default. The kind's own answer, so core names no
     * application kind: Depot's drop box needs `core.public-shares.drop-box` (ADR 0088, #440 gap 2), because a drop box
     * exists to carry a `WRITE_ONLY` link. `NodesHandler.createInScope` asks it beside [parentAllows] and refuses a
     * switched-off one with `422 capability_disabled`, on the member's and the guest's create alike. Only creation asks
     * it: a node that exists stays, and is moved, renamed and shared as before (ADR 0088 decision 5, a feature switch
     * refuses only what is new).
     */
    fun capabilityToCreate(kind: String): String? = null

    /**
     * The application a node of [kind] — one this implementation [handles] — belongs to: its register entry
     * (`contracts/platform/capabilities.json`, the application's own one-segment id), or null for core's own kind, the
     * default. The seam an application switch is asked through ([me.raddatz.veyq.core.platform.capabilities.ApplicationSwitch],
     * uc.platform.switch-application-off): core asks it by the node's kind and names no application. Unlike
     * [capabilityToCreate], it reaches what exists: off, every write of a node of the kind is refused.
     */
    fun applicationOf(kind: String): String? = null

    companion object {
        /** The capability the one rule of [rules] that handles [kind] says its creation needs; none or several → null
         *  (such a kind is refused by [parentAllows] anyway). */
        fun capabilityToCreate(rules: List<NodeKindRules>, kind: String): String? =
            rules.filter { it.handles(kind) }.singleOrNull()?.capabilityToCreate(kind)

        /** The application the one rule of [rules] that handles [kind] names; none or several → null. */
        fun applicationOf(rules: List<NodeKindRules>, kind: String): String? =
            rules.filter { it.handles(kind) }.singleOrNull()?.applicationOf(kind)

        /** Whether the one rule of [rules] that handles [kind] says it takes a cover; none or several → false. */
        fun takesCover(rules: List<NodeKindRules>, kind: String): Boolean =
            rules.filter { it.handles(kind) }.singleOrNull()?.takesCover(kind) ?: false

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

    override fun takesCover(kind: String): Boolean = kind == NodeEntity.FOLDER
}
