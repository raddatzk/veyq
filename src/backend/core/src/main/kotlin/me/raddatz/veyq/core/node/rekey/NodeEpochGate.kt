package me.raddatz.veyq.core.node.rekey

import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import jakarta.transaction.Status

/**
 * Layer 1 of ADR 0109 point 6: **no write lands under a node key from before a rotation.** Every write that seals
 * anything under a node's key — member or guest, a create under a parent, a meta, a medium, a bucket, a share, an
 * identity — states that node's epoch as the writer read it, and asks [check] in the transaction that writes, before
 * it writes anything:
 *
 * - the stated epoch is not the node's stored one → [Verdict.STALE] (`409 stale_epoch`): the writer holds the key
 *   from before (or one it cannot have) and re-reads;
 * - the node's stored epoch is below the scope's current one → [Verdict.PENDING] (`409 node_rekey_pending`): the
 *   rotation's re-key has not reached the node yet, and the writer retries. Only that node waits.
 *
 * Decided **under the node's row lock**, which the re-key's commit (`RekeyStore.commit`) takes too, so a write and the
 * commit of the node it seals under run one after the other: a write that wins lands before the commit and the commit
 * re-seals it with the rest (its staged parts cover it, or the commit refuses and the re-keyer stages again); a write
 * that loses sees the new epoch and is told to re-read. Lock order: the scope row, a share row, the writer's own EXISTING
 * rows it rewrites (a medium's row before its meta write, a bucket's before its rename), then this node row, then what it
 * inserts and the blob rows — the commit's order too (`RekeyStore.commit`, [NodeRekeyParticipant.lockRows]).
 *
 * Its scope is honest-operator: an operator that skips it is met by the device floor (layer 2,
 * ADR 0024 Decision 6), which every
 * client applies to node keys in the one helper it seals through.
 */
@ApplicationScoped
class NodeEpochGate {

    enum class Verdict { OK, STALE, PENDING, MISSING }

    /** Lock [nodeId] (live or trashed — a caller that refuses a trashed node says so itself) and judge [stated] against
     *  it. [Verdict.MISSING] when there is no such node in [scopeId]. Joins the caller's transaction, which must exist:
     *  the lock is worth nothing past the statement that takes it. */
    fun check(scopeId: String, nodeId: String, stated: Int): Verdict {
        check(QuarkusTransaction.getStatus() != Status.STATUS_NO_TRANSACTION) { "NodeEpochGate.check joins the writer's transaction" }
        val node = lockNode(nodeId)?.takeIf { it.scopeId == scopeId } ?: return Verdict.MISSING
        return verdict(stated, node.epoch, ScopeEntity.findById(scopeId)?.currentEpoch)
    }

    companion object {
        /** The rule, without the lock: stale before pending, so a writer holding a key from before re-reads first. */
        fun verdict(stated: Int, stored: Int, current: Int?): Verdict = when {
            stated != stored -> Verdict.STALE
            current != null && stored < current -> Verdict.PENDING
            else -> Verdict.OK
        }

        /** `SELECT … FOR UPDATE` on the node row and a refresh — a copy this transaction already loaded would carry the
         *  epoch from before the lock ([ScopeEntity.lockRow]'s reason). */
        fun lockNode(nodeId: String): NodeEntity? =
            NodeEntity.find("id", nodeId).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult()
                ?.also { NodeEntity.getEntityManager().refresh(it) }
    }
}
