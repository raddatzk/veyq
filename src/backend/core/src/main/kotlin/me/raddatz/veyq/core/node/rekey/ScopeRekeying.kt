package me.raddatz.veyq.core.node.rekey

import me.raddatz.veyq.core.node.NodeEntity

/**
 * Whether a scope is **re-keying** (ADR 0114 decision 4): a live or trashed node of it is due for its re-key — a
 * rotation's commit or a link's close marked it, and its node commit has not cleared the mark yet. Derived from the node
 * rows the server holds, never stored on the scope — so a crashed re-key reads as unfinished until a member's worker
 * resumes it, and a finished one needs no flag cleared; a worker that resumes a rotation's re-key resumes a close's. One
 * range read on `ix_node_scope_due`. Read by the rotation status, every member's scope row and a queued job's
 * completion.
 */
object ScopeRekeying {
    fun lags(scopeId: String): Boolean = NodeEntity.count("scopeId = ?1 and rekeyDue = true", scopeId) > 0
}
