package me.raddatz.veyq.core.node.rekey

import me.raddatz.veyq.core.node.NodeEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import jakarta.transaction.Status

/**
 * Layer 1 of ADR 0114 decision 3: **no write lands under a node key that was replaced, or is about to be.** Every write
 * that seals anything under a node's key — member or guest, a create under a parent, a meta, a medium, a bucket, a
 * share, an identity — states that node's own epoch as the writer read it, and asks [check] in the transaction that
 * writes, before it writes anything:
 *
 * - the stated epoch is not the node's stored one → [Verdict.STALE] (`409 stale_epoch`): the writer holds a key that
 *   was replaced since (or one it cannot have) and re-reads;
 * - the node is **due** for its re-key ([NodeEntity.rekeyDue]) → [Verdict.PENDING] (`409 node_rekey_pending`): a
 *   rotation's commit or a link's close marked it, its re-key has not reached it yet, and the writer retries. Only that
 *   node waits. The mark is the node's own (ADR 0114 decision 4): no comparison with the scope's epoch — a close
 *   replaces a subtree's keys under an unchanged scope key.
 *
 * Decided **under the node's row lock**, which the re-key's commit (`RekeyStore.commit`) and the mark (a rotation's
 * commit, `ScopesStore.rotate`) take too, so a write and the commit of the node it seals under run one after the other:
 * a write that wins lands before the commit and the commit re-seals it with the rest (its staged parts cover it, or the
 * commit refuses and the re-keyer stages again); a write that loses sees the new epoch and is told to re-read. Lock
 * order: the scope row, a share row, the writer's own EXISTING rows it rewrites (a medium's row before its meta write, a
 * bucket's before its rename), then this node row, then what it inserts and the blob rows — the commit's order too
 * (`RekeyStore.commit`, [NodeRekeyParticipant.lockRows]).
 *
 * Its scope is honest-operator: an operator that skips it is met by the device floor (layer 2,
 * ADR 0024 Decision 6, ADR 0115 decision 6), which every client applies to node keys in the one helper it seals through.
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
        return verdict(stated, node.epoch, node.rekeyDue)
    }

    companion object {
        /** The rule, without the lock: stale before pending, so a writer holding a replaced key re-reads first. */
        fun verdict(stated: Int, stored: Int, due: Boolean): Verdict = when {
            stated != stored -> Verdict.STALE
            due -> Verdict.PENDING
            else -> Verdict.OK
        }

        /** `SELECT … FOR UPDATE` on the node row and a refresh — a copy this transaction already loaded would carry the
         *  epoch and the mark from before the lock ([me.raddatz.veyq.core.scope.ScopeEntity.lockRow]'s reason). */
        fun lockNode(nodeId: String): NodeEntity? =
            NodeEntity.find("id", nodeId).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult()
                ?.also { NodeEntity.getEntityManager().refresh(it) }
    }
}
