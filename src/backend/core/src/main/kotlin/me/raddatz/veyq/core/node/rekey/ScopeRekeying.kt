package me.raddatz.veyq.core.node.rekey

import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity

/**
 * Whether a scope is **re-keying** (ADR 0109 point 1): its epoch moved and a live or trashed node still
 * carries a key minted before it. Derived from the node rows the server holds, never stored — so a crashed re-key reads
 * as unfinished until a member's worker resumes it, and a finished one needs no flag cleared. One range read on
 * `ix_node_scope_epoch`. Read by the rotation status, every member's scope row and a queued job's completion.
 */
object ScopeRekeying {
    fun lags(scopeId: String): Boolean {
        val current = ScopeEntity.findById(scopeId)?.currentEpoch ?: return false
        return lagsBelow(scopeId, current)
    }

    fun lagsBelow(scopeId: String, current: Int): Boolean = NodeEntity.count("scopeId = ?1 and epoch < ?2", scopeId, current) > 0
}
