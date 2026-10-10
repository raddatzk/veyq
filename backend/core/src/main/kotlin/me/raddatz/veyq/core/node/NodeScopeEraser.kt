package me.raddatz.veyq.core.node

import me.raddatz.veyq.core.scope.ScopeEraser
import jakarta.enterprise.context.ApplicationScoped

/**
 * The node rows of an erased group. Phase 700: after every application's rows that name a node (Memories' at 100 —
 * its gallery counters' `fk_media_revision_node` refuses this delete while one remains) and core's attachments (200,
 * `fk_attachment_node` likewise), and after core's blob rows, as the purge always ran it: no `veyq_node` column
 * references a blob any more (inbox #341), so blobs may go first.
 */
@ApplicationScoped
class NodeScopeEraser : ScopeEraser {
    override val phase = 700

    override fun eraseScope(scopeId: String) {
        NodeEntity.delete("scopeId", scopeId)
    }
}
