package me.raddatz.veyq.core.blob

import me.raddatz.veyq.core.content.NodesStore
import me.raddatz.veyq.core.node.NodeEntity
import io.quarkus.arc.All
import jakarta.enterprise.context.ApplicationScoped

/**
 * One derivative upload of an item to complete: its role + the upload session id. [kind] is the application's role
 * token (Memories': its derivative kinds), opaque to core — core carries it to the implementation and never reads it.
 */
data class DerivativeDeclare(val kind: String, val uploadId: String)

/**
 * One logical item to complete: the original upload + role-tagged derivative uploads, attached to a node — generic
 * (ADR 0079, cr-platform-contract-split D8). Core's dispatcher ([UploadCompletions]) reads [nodeId] only, to resolve
 * the live target and pick the one implementation that accepts its kind; it hands the item on unchanged. Everything
 * else is the application's to interpret: what [ownerId] names, which [derivatives] kinds exist, and what each of
 * [payloads] holds. Memories and Depot implement it today — e.g. Memories' member completion (`MediaResource`) and its guest
 * one on a share link (`ShareMediaHandler`) map their own request items onto this one and its result back onto
 * theirs, so no wire names a field of it.
 */
data class CompleteItem(
    /** The client-minted id of the object the item becomes — the `ownerId` every upload of the item was started with
     *  (ADR 0072). Memories: the media id. */
    val ownerId: String,
    val nodeId: String,
    val originalUploadId: String,
    val derivatives: List<DerivativeDeclare>,
    /** The application's sealed values for the object, keyed by its own field names, opaque to core: no seal, no
     *  validation (their bounds are the application's request schema's). An absent key is an absent value. */
    val payloads: Map<String, String>,
)

/** Per-item result of `complete`: the original's blob id and the object's [ownerId] on success, else an [error] code. */
data class CompleteItemResult(val blobId: String?, val ownerId: String?, val error: String?)

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
     * [attached] is told last in that same transaction, with the id of the object the item became, once and only when
     * it was attached — a guest's caller records the write there (the guest-write record, ADR 0093 § 10); a refusal,
     * a conflict or a rollback never tells it. A member's complete passes nothing.
     */
    fun complete(
        scopeId: String, node: NodeEntity, item: CompleteItem, opener: UploadOpener, admitted: () -> Boolean,
        attached: (objectId: String) -> Unit = {},
    ): CompleteItemResult
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
     * whom the result is attributed to. Each item's `nodeId` is honoured (the share path sets it to the guest's target
     * — the shared root or a node below it, `ShareGuestGate.target`). [admitted] re-asks the caller's gate first in
     * each attach's transaction (the share path's also re-decides that target under the scope lock); [attached] is told,
     * last in that transaction, each object an item became ([UploadCompletion.complete]).
     */
    fun complete(
        scopeId: String, items: List<CompleteItem>, opener: UploadOpener, admitted: () -> Boolean = { true },
        attached: (objectId: String) -> Unit = {},
    ): List<CompleteItemResult> =
        items.map { completeOne(scopeId, it, opener, admitted, attached) }

    private fun completeOne(
        scopeId: String, item: CompleteItem, opener: UploadOpener, admitted: () -> Boolean, attached: (String) -> Unit,
    ): CompleteItemResult {
        val node = nodes.findInScope(scopeId, item.nodeId)
            ?: return CompleteItemResult(null, null, "node_not_found")
        val completion = completions.filter { it.accepts(node.kind) }.singleOrNull()
            ?: return CompleteItemResult(null, null, "illegal_target")
        return completion.complete(scopeId, node, item, opener, admitted, attached)
    }
}
