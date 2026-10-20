package me.raddatz.veyq.core.blob

import me.raddatz.veyq.core.scope.ScopeEraser
import jakarta.enterprise.context.ApplicationScoped

/**
 * The attachment rows of an erased group. Phase 200, **below** `ScopeEraser.BLOB_ROWS_PHASE`: `fk_attachment_blob`
 * refuses core's blob delete while a row still names a blob, and `fk_attachment_node` (700, nodes) and
 * `fk_attachment_share_identity` (800, links and their guests) refuse the later ones. Rows only — `ScopePurge` has
 * already recorded every blob of the scope for reclaim and deletes them at its own phase.
 */
@ApplicationScoped
class AttachmentScopeEraser : ScopeEraser {
    override val phase = 200

    override fun eraseScope(scopeId: String) {
        NodeAttachmentEntity.delete("scopeId", scopeId)
    }
}
