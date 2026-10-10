package me.raddatz.veyq.core.node

import me.raddatz.veyq.core.scope.ScopeEraser
import jakarta.enterprise.context.ApplicationScoped

/**
 * The node rows of an erased group. Phase 700: after the applications' rows that name nodes (Memories' media) and core's attachments (200, whose
 * `fk_attachment_node` refuses this delete while one remains), and after core's blob rows, as the purge always ran
 * it; `veyq_node.cover_blob_id` has no foreign key, which is why blobs may go first.
 */
@ApplicationScoped
class NodeScopeEraser : ScopeEraser {
    override val phase = 700

    override fun eraseScope(scopeId: String) {
        NodeEntity.delete("scopeId", scopeId)
    }
}
