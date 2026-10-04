package com.veyq.core.node

import com.veyq.core.scope.ScopeEraser
import jakarta.enterprise.context.ApplicationScoped

/**
 * The node rows of an erased group. Phase 700: after media (whose rows name nodes) and after core's blob rows,
 * as the purge always ran it; `veyq_node.cover_blob_id` has no foreign key, which is why blobs may go first.
 */
@ApplicationScoped
class NodeScopeEraser : ScopeEraser {
    override val phase = 700

    override fun eraseScope(scopeId: String) {
        NodeEntity.delete("scopeId", scopeId)
    }
}
