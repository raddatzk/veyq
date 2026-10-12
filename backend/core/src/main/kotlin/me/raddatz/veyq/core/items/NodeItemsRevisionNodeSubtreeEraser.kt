package me.raddatz.veyq.core.items

import me.raddatz.veyq.core.node.NodeSubtreeEraser
import jakarta.enterprise.context.ApplicationScoped

/**
 * The item-list counters of an erased node subtree. Phase 300 — after every application's rows (Memories' media at 100;
 * nothing bumps once they are gone) and core's attachments (200), before `NodeSubtreePurge` deletes the node rows, which
 * `fk_items_revision_node` refuses while a counter row remains (deliberately without a cascade). No guest identity is
 * named by it. The scope's own erasure deletes these rows in `NodeScopeEraser`, ahead of the node rows.
 */
@ApplicationScoped
class NodeItemsRevisionNodeSubtreeEraser : NodeSubtreeEraser {
    override val phase = 300

    override fun erase(scopeId: String, nodeIds: List<String>): Set<String> {
        NodeItemsRevisionEntity.delete("nodeId in ?1", nodeIds)
        return emptySet()
    }
}
