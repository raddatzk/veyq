package com.veyq.core.blob

import com.veyq.core.content.NodesStore
import com.veyq.core.node.NodeEntity
import io.quarkus.arc.All
import jakarta.enterprise.context.ApplicationScoped

/** One derivative upload of a media to complete: its role + the upload session id. */
data class DerivativeDeclare(val kind: String, val uploadId: String)

/**
 * One logical item to complete: the original upload + role-tagged derivative uploads, attached to a node.
 *
 * The field names are Memories' words (`mediaId`, `derivatives`, `wrappedExif`) and stay core's on purpose: core's
 * own share contract serves a guest's completion (`SharingHandler.completeUploads`, `ShareGuestWrites.complete`)
 * and builds these, so moving them to the application would make core import it — and renaming `mediaId` is a wire
 * change. ADR 0079 Consequences names it among what core still knows.
 */
data class CompleteItem(
    /** The client-minted media id (ADR 0072) — the `ownerId` every upload of the item was started with. */
    val mediaId: String,
    val nodeId: String,
    val originalUploadId: String,
    val derivatives: List<DerivativeDeclare>,
    val wrappedMeta: String?,
    val wrappedExif: String?,
)

/** Per-item result of `complete`. */
data class CompleteItemResult(val blobId: String?, val mediaId: String?, val error: String?)

/**
 * What an application does when a verified upload is attached to one of its nodes (ADR 0079): the second half of an
 * upload, after core's generic start and windows. Core resolves the target node and dispatches by its kind
 * ([accepts]); the application owns everything from the session lookup on — which sessions an item names, how their
 * wraps are bound, what rows the blobs become — built on core's primitives ([BlobUploadStore.findSession],
 * [BlobUploadHandler.verifyBlob], [BlobUploadHandler.purge], [BlobUploadStore.persistBlob]).
 *
 * **Exactly one implementation per kind, and none is the default.** A kind that no implementation [accepts] attaches
 * nothing (`illegal_target`): a new kind gets uploads when an application opts it in, never by inheriting them. Two
 * that accept the same kind refuse it too, rather than letting CDI bean order choose — the partition
 * `UploadCompletionPartitionSliceTest` pins.
 */
interface UploadCompletion {
    /** Whether an upload may be attached to a node of [nodeKind] through this implementation. */
    fun accepts(nodeKind: String): Boolean

    /**
     * Complete [item] against [node] — a live node of `scopeId` whose kind this implementation [accepts], resolved by
     * core — resolving each upload only for its [opener]. [admitted] is the caller's gate, asked again first in the
     * attach's transaction (a guest's link under the row lock its delete holds; a member's complete passes none).
     */
    fun complete(scopeId: String, node: NodeEntity, item: CompleteItem, opener: UploadOpener, admitted: () -> Boolean): CompleteItemResult
}

/**
 * The completion dispatcher: per item, the target node — **live** and in this scope, so a trashed target is
 * `node_not_found` and is never offered to an application (ADR 0061: a soft-deleted subtree takes no new uploads) —
 * then the one [UploadCompletion] that [UploadCompletion.accepts] its kind (none or several → `illegal_target`).
 * The caller does its own authorization: a member's access check, or a share's guest gate.
 */
@ApplicationScoped
class UploadCompletions(
    private val nodes: NodesStore,
    // `MutableList`, not `List`: ArC refuses `@All kotlin.List` (see `ScopePurge`'s constructor).
    @All private val completions: MutableList<UploadCompletion>,
) {
    /**
     * Verify + attach each item, scoped only by `scopeId`, resolving each upload only for its [opener] — who is also
     * whom the result is attributed to. Each item's `nodeId` is honoured (the share path sets it to the shared root
     * node). [admitted] re-asks the caller's gate first in each attach's transaction.
     */
    fun complete(
        scopeId: String, items: List<CompleteItem>, opener: UploadOpener, admitted: () -> Boolean = { true },
    ): List<CompleteItemResult> =
        items.map { completeOne(scopeId, it, opener, admitted) }

    private fun completeOne(scopeId: String, item: CompleteItem, opener: UploadOpener, admitted: () -> Boolean): CompleteItemResult {
        val node = nodes.findInScope(scopeId, item.nodeId)
            ?: return CompleteItemResult(null, null, "node_not_found")
        val completion = completions.filter { it.accepts(node.kind) }.singleOrNull()
            ?: return CompleteItemResult(null, null, "illegal_target")
        return completion.complete(scopeId, node, item, opener, admitted)
    }
}
