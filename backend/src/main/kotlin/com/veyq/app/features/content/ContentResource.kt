package com.veyq.app.features.content

import com.veyq.app.generated.api.ContentApi
import com.veyq.app.generated.model.CreateNodeRequest
import com.veyq.app.generated.model.NodeCreated
import com.veyq.app.generated.model.NodeKind
import com.veyq.app.generated.model.NodeSummary
import com.veyq.app.generated.model.RestoreNodeRequest
import com.veyq.app.generated.model.TrashedNodeSummary
import com.veyq.app.generated.model.UpdateNodeRequest
import com.veyq.app.shared.config.VeyqConfig
import com.veyq.app.shared.http.HttpFailure
import com.veyq.app.shared.http.httpError
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Response
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import com.veyq.app.shared.ratelimit.RateLimitExempt

/**
 * HTTP adapter for the Memory tree (en.memory.tree, cr-0027). Implements the generated [ContentApi]; thin
 * (ADR 0001) — delegates to [NodesHandler]. `@Authenticated`; the caller (from the `SecurityIdentity`
 * principal) must hold a grant for the scope (a non-member gets 404). Operator-blind: only ciphertext +
 * opaque tokens cross. The scope root's parent slot is the scope id; the API surfaces it as `parentId = null`.
 */
@Authenticated
@RateLimitExempt(
    reason =
        "Authenticated + membership-gated node CRUD. Every call touches one scope the caller already " +
        "belongs to; no external cost and no anonymous surface.",
)
class ContentResource(
    private val handler: NodesHandler,
    private val identity: SecurityIdentity,
    config: VeyqConfig,
) : ContentApi {

    // ADR 0061 Decision 6 / rejected (e): `purgeAfter` is DERIVED here from `deletedAt` + the operator's
    // configured window, never stored — a column would freeze the window at deletion time and drift from the
    // configuration. Same shape as the group grace period (`ScopesResource`).
    private val trashRetention: Duration = config.node().trash().retention()

    override fun createNode(scopeId: UUID, createNodeRequest: CreateNodeRequest): Response {
        val outcome = handler.create(
            identity.principal.name, scopeId.toString(), createNodeRequest.nodeId.toString(), createNodeRequest.parentId?.toString(),
            createNodeRequest.kind.value, createNodeRequest.encryptedName, createNodeRequest.slugToken,
            createNodeRequest.orderIndex ?: 0, createNodeRequest.epoch ?: 0,
            createNodeRequest.wrappedContentKey, createNodeRequest.wrappedMeta,
        )
        return when (outcome) {
            is CreateNodeOutcome.Created ->
                Response.status(Response.Status.CREATED).entity(NodeCreated(nodeId = UUID.fromString(outcome.nodeId))).build()
            is HttpFailure -> httpError(outcome)
        }
    }

    override fun listNodes(scopeId: UUID): Response {
        val sid = scopeId.toString()
        return when (val o = handler.list(identity.principal.name, sid)) {
            is ListNodesOutcome.Nodes -> Response.ok(o.rows.map { it.toSummary(sid) }).build()
            is HttpFailure -> httpError(o)
        }
    }

    override fun updateNode(scopeId: UUID, nodeId: UUID, updateNodeRequest: UpdateNodeRequest): Response =
        when (val o = handler.update(
            identity.principal.name, scopeId.toString(), nodeId.toString(),
            updateNodeRequest.encryptedName, updateNodeRequest.slugToken,
            updateNodeRequest.parentId?.toString(), updateNodeRequest.orderIndex, updateNodeRequest.wrappedMeta,
        )) {
            UpdateNodeOutcome.Updated -> Response.noContent().build()
            is HttpFailure -> httpError(o)
        }

    override fun deleteNode(scopeId: UUID, nodeId: UUID): Response =
        when (val o = handler.delete(identity.principal.name, scopeId.toString(), nodeId.toString())) {
            DeleteNodeOutcome.Deleted -> Response.noContent().build()
            is HttpFailure -> httpError(o)
        }

    override fun restoreNode(scopeId: UUID, nodeId: UUID, restoreNodeRequest: RestoreNodeRequest): Response =
        when (val o = handler.restore(
            identity.principal.name, scopeId.toString(), nodeId.toString(),
            restoreNodeRequest.slugToken, restoreNodeRequest.encryptedName,
        )) {
            RestoreNodeOutcome.Restored -> Response.noContent().build()
            is HttpFailure -> httpError(o)
        }

    override fun listTrashedNodes(scopeId: UUID): Response {
        val sid = scopeId.toString()
        return when (val o = handler.listTrash(identity.principal.name, sid)) {
            is ListTrashOutcome.Trash -> Response.ok(o.rows.map { it.toTrashSummary(sid) }).build()
            is HttpFailure -> httpError(o)
        }
    }

    private fun TrashedNodeRow.toTrashSummary(scopeId: String) = TrashedNodeSummary(
        id = UUID.fromString(id),
        kind = NodeKind.decode(kind),
        encryptedName = encryptedName,
        epoch = epoch,
        deletedAt = OffsetDateTime.ofInstant(deletedAt, ZoneOffset.UTC),
        purgeAfter = OffsetDateTime.ofInstant(deletedAt.plus(trashRetention), ZoneOffset.UTC),
        parentId = if (parentId == scopeId) null else UUID.fromString(parentId),
    )

    /** Map a stored row to the API shape: the root's parent slot (= scope id) surfaces as `parentId = null`. */
    private fun NodeRow.toSummary(scopeId: String) = NodeSummary(
        id = UUID.fromString(id),
        kind = NodeKind.decode(kind),
        encryptedName = encryptedName,
        slugToken = slugToken,
        orderIndex = orderIndex,
        epoch = epoch,
        parentId = if (parentId == scopeId) null else UUID.fromString(parentId),
        wrappedContentKey = wrappedContentKey,
        wrappedMeta = wrappedMeta,
        sealedDropPrivateKey = sealedDropPrivateKey,
    )
}
