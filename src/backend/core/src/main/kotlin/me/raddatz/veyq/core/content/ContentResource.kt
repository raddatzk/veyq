package me.raddatz.veyq.core.content

import me.raddatz.veyq.core.generated.api.ContentApi
import me.raddatz.veyq.core.generated.model.CreateNodeRequest
import me.raddatz.veyq.core.generated.model.NodeCreated
import me.raddatz.veyq.core.generated.model.NodeCoverConflict
import me.raddatz.veyq.core.generated.model.NodeSummary
import me.raddatz.veyq.core.generated.model.RemoveNodeCoverRequest
import me.raddatz.veyq.core.generated.model.SetNodeCoverRequest
import me.raddatz.veyq.core.blob.toSpec
import me.raddatz.veyq.core.validation.validated
import me.raddatz.veyq.core.generated.model.RestoreNodeRequest
import me.raddatz.veyq.core.generated.model.TrashedNodeSummary
import me.raddatz.veyq.core.generated.model.UpdateNodeRequest
import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpError
import me.raddatz.veyq.core.http.httpStaleSealedMeta
import me.raddatz.veyq.core.moderation.block.ModerationBlocks
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Response
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import me.raddatz.veyq.core.ratelimit.RateLimitExempt

/**
 * HTTP adapter for the node tree (en.memory.tree, cr-0027). Implements the generated [ContentApi]; thin
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
    private val covers: NodeCoverHandler,
    private val identity: SecurityIdentity,
    private val blocks: ModerationBlocks,
    config: VeyqConfig,
) : ContentApi {

    // ADR 0061 Decision 6 / rejected (e): `purgeAfter` is DERIVED here from `deletedAt` + the operator's
    // configured window, never stored — a column would freeze the window at deletion time and drift from the
    // configuration. Same shape as the group grace period (`ScopesResource`).
    private val trashRetention: Duration = config.node().trash().retention()

    override fun createNode(scopeId: UUID, createNodeRequest: CreateNodeRequest): Response {
        val outcome = handler.create(
            identity.principal.name, scopeId.toString(), createNodeRequest.nodeId.toString(), createNodeRequest.parentId?.toString(),
            createNodeRequest.kind, createNodeRequest.encryptedName, createNodeRequest.slugToken,
            createNodeRequest.orderIndex ?: 0, createNodeRequest.epoch ?: 0,
            createNodeRequest.wrappedContentKey, createNodeRequest.wrappedMeta, createNodeRequest.parentEpoch,
        )
        return when (outcome) {
            is CreateNodeOutcome.Created ->
                Response.status(Response.Status.CREATED).entity(NodeCreated(nodeId = UUID.fromString(outcome.nodeId))).build()
            is HttpFailure -> httpError(outcome)
        }
    }

    override fun listNodes(scopeId: UUID, ifNoneMatch: String?): Response {
        val sid = scopeId.toString()
        return when (val o = handler.list(identity.principal.name, sid, TreeETag.parse(ifNoneMatch))) {
            is ListNodesOutcome.Nodes -> blocks.contentView(sid).let { view ->
                Response.ok(o.rows.map { it.toSummary(sid, view.nodeBlocked(it.id)) }).header("ETag", TreeETag.of(o.revision)).build()
            }
            ListNodesOutcome.NotModified -> Response.notModified().build() // 304 — no body, no row read (ADR 0081)
            is HttpFailure -> httpError(o)
        }
    }

    override fun updateNode(scopeId: UUID, nodeId: UUID, updateNodeRequest: UpdateNodeRequest): Response =
        when (val o = handler.update(
            identity.principal.name, scopeId.toString(), nodeId.toString(),
            NodesHandler.PlacementRequest(
                parentId = updateNodeRequest.parentId?.toString(),
                encryptedName = updateNodeRequest.encryptedName,
                slugToken = updateNodeRequest.slugToken,
                wrappedContentKey = updateNodeRequest.wrappedContentKey,
                epoch = updateNodeRequest.epoch,
                placementRevision = updateNodeRequest.placementRevision,
                parentEpoch = updateNodeRequest.parentEpoch,
            ),
            updateNodeRequest.orderIndex, updateNodeRequest.wrappedMeta, updateNodeRequest.metaRevision, updateNodeRequest.nodeEpoch,
        )) {
            UpdateNodeOutcome.Updated -> Response.noContent().build()
            is UpdateNodeOutcome.StaleMeta -> httpStaleSealedMeta(o, o.wrappedMeta, o.metaRevision)
            is HttpFailure -> httpError(o)
        }

    override fun setNodeCover(scopeId: UUID, nodeId: UUID, setNodeCoverRequest: SetNodeCoverRequest): Response =
        coverResponse(
            covers.set(
                identity.principal.name, scopeId.toString(), nodeId.toString(), setNodeCoverRequest.uploadId?.toString(),
                setNodeCoverRequest.shared?.toSpec(),
                setNodeCoverRequest.expectedBlobId?.toString(), setNodeCoverRequest.wrappedMeta,
                setNodeCoverRequest.metaRevision.validated(), setNodeCoverRequest.nodeEpoch.validated(),
            ),
        )

    override fun removeNodeCover(scopeId: UUID, nodeId: UUID, removeNodeCoverRequest: RemoveNodeCoverRequest): Response =
        coverResponse(
            covers.remove(
                identity.principal.name, scopeId.toString(), nodeId.toString(), removeNodeCoverRequest.blobId.toString(),
                removeNodeCoverRequest.wrappedMeta, removeNodeCoverRequest.metaRevision.validated(),
                removeNodeCoverRequest.nodeEpoch.validated(),
            ),
        )

    /** `204`, or the refusal; the two 409s that carry more than their code answer the contract's `NodeCoverConflict`. */
    private fun coverResponse(o: NodeCoverOutcome): Response = when (o) {
        NodeCoverOutcome.Done -> Response.noContent().build()
        is NodeCoverOutcome.CoverStale ->
            Response.status(o.status.httpStatus)
                .entity(NodeCoverConflict(code = o.code, currentBlobId = o.currentBlobId?.let(UUID::fromString))).build()
        is NodeCoverOutcome.StaleMeta ->
            Response.status(o.status.httpStatus)
                .entity(NodeCoverConflict(code = o.code, wrappedMeta = o.wrappedMeta, metaRevision = o.metaRevision)).build()
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
            restoreNodeRequest.slugToken, restoreNodeRequest.encryptedName, restoreNodeRequest.parentEpoch.validated(),
        )) {
            RestoreNodeOutcome.Restored -> Response.noContent().build()
            is HttpFailure -> httpError(o)
        }

    override fun listTrashedNodes(scopeId: UUID): Response {
        val sid = scopeId.toString()
        return when (val o = handler.listTrash(identity.principal.name, sid)) {
            is ListTrashOutcome.Trash -> blocks.contentView(sid).let { view ->
                Response.ok(o.rows.map { it.toTrashSummary(sid, view.nodeBlocked(it.id)) }).build()
            }
            is HttpFailure -> httpError(o)
        }
    }

    private fun TrashedNodeRow.toTrashSummary(scopeId: String, blocked: Boolean) = TrashedNodeSummary(
        id = UUID.fromString(id),
        kind = kind,
        encryptedName = encryptedName,
        epoch = epoch,
        placementRevision = placementRevision,
        deletedAt = OffsetDateTime.ofInstant(deletedAt, ZoneOffset.UTC),
        purgeAfter = OffsetDateTime.ofInstant(deletedAt.plus(trashRetention), ZoneOffset.UTC),
        parentId = if (parentId == scopeId) null else UUID.fromString(parentId),
        shareIdentityId = shareIdentityId?.let(UUID::fromString),
        wrappedIdentityKey = wrappedIdentityKey,
        attached = attached,
        blockedByOperator = blocked,
    )

    /**
     * Map a stored row to the API shape: the root's parent slot (= scope id) surfaces as `parentId = null`. The `kind`
     * is served as stored — an open token (ADR 0079): a kind no application claims is the client registry's to render
     * as unsupported, never a decode failure here.
     */
    private fun NodeRow.toSummary(scopeId: String, blocked: Boolean) = NodeSummary(
        id = UUID.fromString(id),
        kind = kind,
        encryptedName = encryptedName,
        slugToken = slugToken,
        orderIndex = orderIndex,
        epoch = epoch,
        parentId = if (parentId == scopeId) null else UUID.fromString(parentId),
        wrappedContentKey = wrappedContentKey,
        wrappedMeta = wrappedMeta,
        sealedDropPrivateKey = sealedDropPrivateKey,
        metaRevision = metaRevision,
        placementRevision = placementRevision,
        shareIdentityId = shareIdentityId?.let(UUID::fromString),
        wrappedIdentityKey = wrappedIdentityKey,
        createdAt = OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC),
        // Kept and marked, not left out (uc.moderation.take-a-measure): the level shows the placeholder.
        blockedByOperator = blocked,
    )
}
