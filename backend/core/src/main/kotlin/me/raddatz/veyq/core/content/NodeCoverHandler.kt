package me.raddatz.veyq.core.content

import me.raddatz.veyq.core.identity.PrincipalKey
import me.raddatz.veyq.core.blob.BlobUploadHandler
import me.raddatz.veyq.core.blob.BlobUploadHandler.BlobVerify
import me.raddatz.veyq.core.blob.BlobUploadStore
import me.raddatz.veyq.core.blob.SharedBlobSpec
import me.raddatz.veyq.core.blob.UploadOpener
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.FailureStatus.BAD_REQUEST
import me.raddatz.veyq.core.http.FailureStatus.CONFLICT
import me.raddatz.veyq.core.http.FailureStatus.UNPROCESSABLE
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.membership.AccessMode.WRITE
import me.raddatz.veyq.core.membership.ScopeAccess
import me.raddatz.veyq.core.membership.refusal
import me.raddatz.veyq.core.node.NodeKindRules
import io.quarkus.arc.All
import jakarta.enterprise.context.ApplicationScoped

/** What `setNodeCover` / `removeNodeCover` did. Only [Done] wrote. */
sealed interface NodeCoverOutcome {
    object Done : NodeCoverOutcome

    /** No such scope grant, live node or open upload of the caller - a 404 that reveals nothing. */
    object NotFound : NodeCoverOutcome, HttpFailure by Failures.nodeNotFound
    object Suspended : NodeCoverOutcome, HttpFailure by Failures.tenantSuspended
    object ScopeDeleting : NodeCoverOutcome, HttpFailure by Failures.scopeDeleting

    /** The node's kind takes no cover (no rule opts it in) - a 422. */
    object IllegalTarget : NodeCoverOutcome, HttpFailure by httpFailure("illegal_target", UNPROCESSABLE)

    /** The upload was not started as this node's cover: its key wrap would not open here (ADR 0072) - a 422. */
    object UploadMismatch : NodeCoverOutcome, HttpFailure by httpFailure("upload_mismatch", UNPROCESSABLE)

    /** The upload failed declare-then-verify (its staged objects are on the reclaim list) - a 400. */
    object Budget : NodeCoverOutcome, HttpFailure by httpFailure("budget_exceeded", BAD_REQUEST)

    /** Neither or both of an upload and a shared row - a 400. */
    object InvalidRequest : NodeCoverOutcome, HttpFailure by Failures.invalidRequest

    /** The shared row's source is no live blob of this node, is blocked, or its new id is taken (ADR 0013 § 5) - a 422. */
    object SharedSourceInvalid : NodeCoverOutcome, HttpFailure by httpFailure("shared_source_invalid", UNPROCESSABLE)

    /** The slot holds another blob than the one the caller stated; nothing attached, a stated upload spent - a 409. */
    data class CoverStale(val currentBlobId: String?) : NodeCoverOutcome, HttpFailure by httpFailure("cover_stale", CONFLICT)

    /** The node's `wrappedMeta` moved past the stated revision; NOTHING was written, the upload is still open (ADR 0077). */
    data class StaleMeta(val wrappedMeta: String?, val metaRevision: Long) : NodeCoverOutcome, HttpFailure by Failures.staleRevision
}

/**
 * Use-case logic for a node's cover (inbox #411): the checks that need no lock, then [NodeCoverStore]'s one
 * transaction. Scope access is the first gate ([ScopeAccess], WRITE: a member who may write the scope may set a cover
 * of any node in it); which kinds take a cover is the one [NodeKindRules] that handles the node's kind.
 */
@ApplicationScoped
class NodeCoverHandler(
    private val access: ScopeAccess,
    private val nodes: NodesStore,
    private val blobs: BlobUploadStore,
    private val uploads: BlobUploadHandler,
    private val store: NodeCoverStore,
    // `MutableList`, not `List`: ArC refuses `@All kotlin.List` (see `ScopePurge`'s constructor).
    @All private val kindRules: MutableList<NodeKindRules>,
) {
    /**
     * Make [uploadId] - or, in its place, a new row on an object the node already stores ([shared], ADR 0013 § 5) - the
     * cover of [nodeId], replacing [expectedBlobId] (null: an empty slot), and write the node's re-sealed [wrappedMeta] at
     * [metaRevision] - all or nothing ([NodeCoverStore.set]). The upload is the caller's own (a session another member
     * opened is not found), verified against its declaration before anything is attached; a shared row's source is
     * checked under the scope lock ([me.raddatz.veyq.core.blob.SharedBlobs]).
     */
    fun set(
        userId: String, scopeId: String, nodeId: String, uploadId: String?, shared: SharedBlobSpec?, expectedBlobId: String?,
        wrappedMeta: String, metaRevision: Long,
    ): NodeCoverOutcome {
        if ((uploadId == null) == (shared == null)) return NodeCoverOutcome.InvalidRequest
        access.check(userId, scopeId, WRITE)
            .refusal(NodeCoverOutcome.NotFound, NodeCoverOutcome.Suspended, NodeCoverOutcome.ScopeDeleting)?.let { return it }
        val node = nodes.findInScope(scopeId, nodeId) ?: return NodeCoverOutcome.NotFound
        if (!NodeKindRules.takesCover(kindRules, node.kind)) return NodeCoverOutcome.IllegalTarget
        val editor = PrincipalKey.User(userId)
        if (shared != null) {
            return store.setShared(scopeId, node.id, node.kind, shared, userId, expectedBlobId, wrappedMeta, metaRevision, editor)
        }
        val session = blobs.findSession(scopeId, checkNotNull(uploadId), UploadOpener.Member(userId)) ?: return NodeCoverOutcome.NotFound
        if (!session.boundTo(node.id, NodeCover.SLOT)) return NodeCoverOutcome.UploadMismatch
        val manifest = when (val v = uploads.verifyBlob(session)) {
            is BlobVerify.Ok -> v.manifest
            BlobVerify.Budget -> {
                uploads.purge(listOf(session))
                return NodeCoverOutcome.Budget
            }
        }
        return store.set(scopeId, node.id, node.kind, session, manifest, expectedBlobId, wrappedMeta, metaRevision, editor)
    }

    /** Empty [nodeId]'s cover slot (only while it holds [blobId]) and write the node's [wrappedMeta] without the cover. */
    fun remove(userId: String, scopeId: String, nodeId: String, blobId: String, wrappedMeta: String, metaRevision: Long): NodeCoverOutcome {
        access.check(userId, scopeId, WRITE)
            .refusal(NodeCoverOutcome.NotFound, NodeCoverOutcome.Suspended, NodeCoverOutcome.ScopeDeleting)?.let { return it }
        if (nodes.findInScope(scopeId, nodeId) == null) return NodeCoverOutcome.NotFound
        return store.remove(scopeId, nodeId, blobId, wrappedMeta, metaRevision, PrincipalKey.User(userId))
    }
}
