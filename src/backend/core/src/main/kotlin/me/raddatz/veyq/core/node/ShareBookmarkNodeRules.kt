package me.raddatz.veyq.core.node

import jakarta.enterprise.context.ApplicationScoped

/** Core's kinds beside `NodeEntity.FOLDER`, as the wire spells them (`contracts/platform/kinds/core.json`). */
object CoreNodeKinds {
    /**
     * A public link a member received, kept as a node (ADR 0120): the link and its password sealed in the node's meta under
     * its own key, nothing of it in the clear. It holds no child, no blob and no cover, roots no link (no `ShareRoots`
     * answers for it), and a link of a folder above it never serves it (`SharingStore.subtree`, decision 7).
     */
    const val SHARE_BOOKMARK = "SHARE_BOOKMARK"
}

/**
 * Core's bookmark (ADR 0120 decision 2): at the scope root or directly in a `FOLDER`, nowhere else, and no node is placed
 * inside one (no rule names it as a parent). It belongs to no application, so no switch refuses its writes.
 */
@ApplicationScoped
class ShareBookmarkNodeRules : NodeKindRules {
    override fun handles(kind: String): Boolean = kind == CoreNodeKinds.SHARE_BOOKMARK

    override fun parentAllows(kind: String, parent: ParentSlot): Boolean =
        parent is ParentSlot.Root || (parent is ParentSlot.Node && parent.kind == NodeEntity.FOLDER)
}
