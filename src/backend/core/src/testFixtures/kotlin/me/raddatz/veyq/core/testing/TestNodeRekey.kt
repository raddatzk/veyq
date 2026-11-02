package me.raddatz.veyq.core.testing

import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.ShareWriteProof
import io.quarkus.narayana.jta.QuarkusTransaction

/**
 * A node re-key's end state for backend tests (ADR 0114 decision 4): what a member's worker leaves once it has committed
 * every due node — each one's own epoch one higher, its `scopeEpoch` the scope's current one, its due mark cleared. The
 * server opens nothing it re-keys, so a test that only needs the re-key to have happened — a reopen after a close, a
 * write after a rotation — sets that state directly; the commit itself is `RekeyCoreSliceTest`'s.
 */
object TestNodeRekey {
    /** Re-key every due node of [scopeId] as its commits would; answers how many were due. */
    fun finish(scopeId: String): Int = QuarkusTransaction.requiringNew().call {
        val current = ScopeEntity.findById(scopeId)!!.currentEpoch
        val due = NodeEntity.find("scopeId = ?1 and rekeyDue = true", scopeId).list()
        due.forEach {
            it.epoch += 1
            it.scopeEpoch = current
            it.rekeyDue = false
        }
        due.size
    }

    /** [nodeId]'s epoch for a write under its key after every earlier close or rotation of its scope was re-keyed
     *  ([finish] first) — for a test that writes on a node again and again, each round's link closed. */
    fun settled(nodeId: String): Int {
        finish(QuarkusTransaction.requiringNew().call { NodeEntity.findById(nodeId)!!.scopeId })
        return epochOf(nodeId)
    }

    /** [nodeId]'s own epoch as stored — what a write sealing under its key states. */
    fun epochOf(nodeId: String): Int = QuarkusTransaction.requiringNew().call { NodeEntity.findById(nodeId)!!.epoch }

    /** Whether [nodeId] is due for its re-key. */
    fun isDue(nodeId: String): Boolean = QuarkusTransaction.requiringNew().call { NodeEntity.findById(nodeId)!!.rekeyDue }

    /** A re-activation's body for [shareId] after its close (ADR 0114 decision 7): every due node of its scope re-keyed
     *  ([finish]), then the root's own epoch stated with its key for the link's role and its name
     *  ([ShareWriteProof.reopen]). For an id no row holds, a well-formed body at epoch 0. */
    fun reopenBody(shareId: String): String {
        val link = QuarkusTransaction.requiringNew().call {
            ShareEntity.findById(shareId)?.let { Triple(it.scopeId, it.nodeId, it.role == ShareEntity.WRITE_ONLY) }
        } ?: return "{${ShareWriteProof.reopen(0)}}"
        finish(link.first)
        return "{${ShareWriteProof.reopen(epochOf(link.second), writeOnly = link.third)}}"
    }
}
