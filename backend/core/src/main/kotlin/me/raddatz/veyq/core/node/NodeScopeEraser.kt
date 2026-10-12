package me.raddatz.veyq.core.node

import me.raddatz.veyq.core.items.NodeItemsRevisionEntity
import me.raddatz.veyq.core.scope.ScopeEraser
import jakarta.enterprise.context.ApplicationScoped

/**
 * The node rows of an erased group, and its tree revision (ADR 0081). Phase 700: after every application's rows that name a node (Memories' at 100) and
 * core's attachments (200, `fk_attachment_node` refuses this delete while one remains; so does the node's item-list
 * counter row, `fk_items_revision_node`, which this eraser deletes itself just below), and after core's blob rows, as the purge always ran it: no `veyq_node` column
 * references a blob any more (inbox #341), so blobs may go first.
 */
@ApplicationScoped
class NodeScopeEraser : ScopeEraser {
    override val phase = 700

    override fun eraseScope(scopeId: String) {
        // The node's item-list counters (inbox #410) carry no scope_id: by their nodes, before the node rows go.
        NodeItemsRevisionEntity.delete("nodeId in (select n.id from NodeEntity n where n.scopeId = ?1)", scopeId)
        NodeEntity.delete("scopeId", scopeId)
        ScopeTreeRevisionEntity.delete("scopeId", scopeId) // the scope's tree counter (ADR 0081); fk_tree_revision_scope refuses the scope row's delete while it stays
    }
}
