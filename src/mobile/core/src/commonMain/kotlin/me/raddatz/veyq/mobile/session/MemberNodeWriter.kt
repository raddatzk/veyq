package me.raddatz.veyq.mobile.session

import me.raddatz.veyq.mobile.PlatformApi
import kotlin.native.HiddenFromObjC
/**
 * A node a member's run created and keeps writing into — a copy's Memory (inbox #988) —, a port of the web's
 * `memberNodeWriter` (`src/web/src/core/node/rereadNode.ts`). A rotation another member makes while the run is under way
 * leaves the node at the epoch it was born at, so each write goes through [NodeEpochWrite]: the device floor, the pull of
 * the node's path on an epoch refusal (`409 node_rekey_pending`, `409 stale_epoch`), the re-read and the write built anew
 * under the node's key as it is then. The writer keeps the key and epoch the last write landed under ([at]), so the run's
 * next write starts there rather than at the key the re-key replaced.
 *
 * Only for writes that are sealed afresh on each attempt (an upload, a meta write that re-reads), never one carrying a
 * ciphertext read under the old key.
 */
class MemberNodeWriter internal constructor(
    at: At,
    /** Re-keys the node and its lagging ancestors on this device (`NodeRekey.pull`); true when it did. */
    private val pull: suspend () -> Boolean,
    /** The node as the server serves it now, the tree read anew; null where it is gone. */
    private val reread: suspend () -> Read?,
    /** The scope epoch this device holds now: a node minted under an older one is below the floor. */
    private val heldScopeEpoch: () -> Int?,
    private val waitsMs: List<Long> = NodeEpochWrite.REKEY_WAITS_MS,
    /** The waits' sleep; null: [NodeEpochWrite]'s own (a test's returns at once). */
    private val sleep: (suspend (Long) -> Unit)? = null,
) {
    /** A node's key and the epoch a write into it states. */
    class At(@PlatformApi @HiddenFromObjC val key: ByteArray, val epoch: Int)

    /** The node re-read: its key and epoch, and the scope epoch its key was minted under. */
    class Read(val at: At, val scopeEpoch: Int)

    /** The key and epoch the node's last write landed under — its birth's until a re-key moved them. */
    var at: At = at
        private set

    /**
     * Runs [attempt] under the node's key: once at [at], and again under the node as re-read after each epoch refusal
     * [NodeEpochWrite] handles. The attempt answers the code of a write that did not go through, null where it did
     * ([answerOf]); the result answers it too, or why the loop gave up.
     */
    suspend fun write(attempt: suspend (At) -> String?): NodeEpochWrite.Result<String?> {
        var now = at
        var minted: Int? = null
        val below: () -> Boolean = { minted?.let { m -> heldScopeEpoch()?.let { m < it } } ?: false }
        val again: suspend () -> Boolean = {
            val read = reread()
            if (read != null) {
                now = read.at
                minted = read.scopeEpoch
            }
            read != null
        }
        val once: suspend () -> Pair<NodeEpochWrite.Answer, String?> = {
            val code = attempt(now)
            if (code == null) at = now
            answerOf(code) to code
        }
        // each a lambda of its own: a function-typed value handed straight on broke the JVM backend's suspend lowering
        val waits = sleep
        return if (waits == null) {
            NodeEpochWrite.run(waitsMs = waitsMs, belowFloor = { below() }, reread = { again() }, attempt = { once() }, pull = { pull() })
        } else {
            NodeEpochWrite.run(
                waitsMs = waitsMs, belowFloor = { below() }, reread = { again() }, attempt = { once() }, sleep = { waits(it) }, pull = { pull() },
            )
        }
    }

    companion object {
        /** The writer of node [nodeId] in [scopeId] that [tenant]'s run created at [key] and [epoch]. */
        fun of(tenant: TenantSession, scopeId: String, nodeId: String, key: ByteArray, epoch: Int): MemberNodeWriter = MemberNodeWriter(
            At(key, epoch),
            pull = { NodeRekey.pull(tenant, scopeId, listOf(nodeId)) },
            reread = {
                tenant.forgetTree()
                tenant.node(scopeId, nodeId)?.let { Read(At(it.key, it.epoch), it.scopeEpoch) }
            },
            heldScopeEpoch = { tenant.scopeKey(scopeId)?.second },
        )

        /** A write's code as [NodeEpochWrite] reads it: the two epoch refusals, done (null), or any other. */
        fun answerOf(code: String?): NodeEpochWrite.Answer = when (code) {
            null -> NodeEpochWrite.Answer.DONE
            "node_rekey_pending" -> NodeEpochWrite.Answer.REKEY_PENDING
            "stale_epoch" -> NodeEpochWrite.Answer.STALE_EPOCH
            else -> NodeEpochWrite.Answer.OTHER
        }
    }
}
