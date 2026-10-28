package me.raddatz.veyq.core.node.rekey

import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.FailureStatus.CONFLICT
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.membership.AccessMode
import me.raddatz.veyq.core.membership.AccessMode.READ
import me.raddatz.veyq.core.membership.AccessMode.WRITE
import me.raddatz.veyq.core.scope.ScopeEntity
import jakarta.enterprise.context.ApplicationScoped

/** What a re-key operation answers: its result, or a refusal ([RekeyRight]'s, or one of its own). Nothing is written
 *  on any refusal. */
sealed interface RekeyOutcome {
    data class Nodes(val currentEpoch: Int, val rows: List<RekeyNodeRow>) : RekeyOutcome
    data class Parts(val parts: RekeyParts) : RekeyOutcome
    object Staged : RekeyOutcome
    object Committed : RekeyOutcome
    object NotFound : RekeyOutcome, HttpFailure by Failures.nodeNotFound
    object InvalidRequest : RekeyOutcome, HttpFailure by Failures.invalidRequest
    /** The node's epoch is not the one stated, it lags nothing (nothing left to re-key), or `epoch` is not current. */
    object Stale : RekeyOutcome, HttpFailure by Failures.staleEpoch
    /** The parent is not re-keyed yet — top-down. */
    object Pending : RekeyOutcome, HttpFailure by Failures.nodeRekeyPending
    object StalePlacement : RekeyOutcome, HttpFailure by Failures.stalePlacement
    data class StaleMeta(val wrappedMeta: String?, val metaRevision: Long) : RekeyOutcome, HttpFailure by Failures.staleRevision
    object PartsMissing : RekeyOutcome, HttpFailure by httpFailure("rekey_parts_missing", CONFLICT)
    object PartsStale : RekeyOutcome, HttpFailure by httpFailure("rekey_parts_stale", CONFLICT)
    object SlugTaken : RekeyOutcome, HttpFailure by Failures.slugTaken
}

/**
 * A scope's re-key after a rotation (ADR 0109, uc.spaces.rekey-after-rotation): list, a node's parts, stage, commit.
 * Every operation asks [RekeyAccess] first — any member holding the current grant, and an owner for a node only an
 * owner re-keys ([OwnerOnlyRekey]). A live rotation job of the scope does not stop a re-key.
 * An application stages its own fields through [stagePart] under the same re-key id and commits through core's commit,
 * where its [NodeRekeyParticipant] swaps them in.
 */
@ApplicationScoped
class RekeyHandler(private val rights: RekeyAccess, private val store: RekeyStore, private val stage: NodeRekeyStage) {

    fun list(userId: String, scopeId: String): RekeyOutcome {
        refusal(userId, scopeId, READ, nodeId = null)?.let { return it }
        val current = ScopeEntity.findById(scopeId)?.currentEpoch ?: return RekeyRight.NoScope
        return RekeyOutcome.Nodes(current, store.listNodes(scopeId))
    }

    fun parts(userId: String, scopeId: String, nodeId: String): RekeyOutcome {
        refusal(userId, scopeId, READ, nodeId)?.let { return it }
        store.node(scopeId, nodeId) ?: return RekeyOutcome.NotFound
        return RekeyOutcome.Parts(store.parts(scopeId, nodeId))
    }

    /** The caller's re-key right as a refusal, or null when granted — at [nodeId] when the operation names one (an
     *  application's participant passes its node too, so an owner-only node refuses there as here). */
    fun refusal(userId: String, scopeId: String, mode: AccessMode, nodeId: String?): RekeyOutcome? =
        rights.check(userId, scopeId, mode, nodeId) as? RekeyOutcome

    /** Core's own staged parts ([RekeyStore.CHILD_NAME] … [RekeyStore.IDENTITY_NAME]). */
    fun stage(userId: String, scopeId: String, nodeId: String, rekeyId: String, fromEpoch: Int, values: Map<NodeRekeyStage.Key, NodeRekeyStage.Value>): RekeyOutcome =
        stagePart(userId, scopeId, nodeId, rekeyId, fromEpoch, NodeRekeyStage.CORE, values)

    /**
     * Stage [values] of [part] (core's, or an application's — its participant's [NodeRekeyParticipant.part]) for
     * [nodeId] under [rekeyId], after the re-key rights and a check that the node still lags at [fromEpoch]: a node
     * another member re-keyed meanwhile has nothing left to stage ([RekeyStageOutcome.Stale]).
     */
    fun stagePart(
        userId: String, scopeId: String, nodeId: String, rekeyId: String, fromEpoch: Int, part: String,
        values: Map<NodeRekeyStage.Key, NodeRekeyStage.Value>,
    ): RekeyOutcome {
        refusal(userId, scopeId, WRITE, nodeId)?.let { return it }
        val node = store.node(scopeId, nodeId) ?: return RekeyOutcome.NotFound
        val current = ScopeEntity.findById(scopeId)?.currentEpoch ?: return RekeyRight.NoScope
        if (node.epoch != fromEpoch || node.epoch >= current) return RekeyOutcome.Stale
        stage.put(rekeyId, scopeId, nodeId, part, values)
        return RekeyOutcome.Staged
    }

    /**
     * The commit's own shape first, before any answer about the instance's state (inbox #534, as `NodesHandler`'s create
     * and placement): a `parentId` without its `parentEpoch` (the contract's `dependentSchemas`), and the scope as the
     * parent — the root's one form is null. [RekeyStore.commit] still refuses a `parentId` that is not the node's.
     */
    fun commit(userId: String, scopeId: String, nodeId: String, rekeyId: String, c: RekeyCommit): RekeyOutcome {
        if (c.parentId == scopeId || (c.parentId != null && c.parentEpoch == null)) return RekeyOutcome.InvalidRequest
        refusal(userId, scopeId, WRITE, nodeId)?.let { return it }
        return store.commit(scopeId, nodeId, rekeyId, c)
    }
}
