package com.veyq.app.core.sharing

/** One media under a shared subtree — the original blob ref + role-tagged derivatives, tagged with its owning node. */
data class ShareMediaRow(
    val nodeId: String,
    val mediaId: String,
    val blobId: String,
    val chunkCount: Int,
    val byteSize: Long,
    val manifest: List<Long>?,
    val wrappedBlobKey: String,
    val derivatives: List<ShareDerivativeRow>,
    val wrappedMeta: String?,
    /** The guest identity that added this media; null for a member's. `resolve` serves it for an uploader's only. */
    val identityId: String?,
)

/** One derivative tier's blob ref under a shared subtree. */
data class ShareDerivativeRow(
    val kind: String,
    val blobId: String,
    val chunkCount: Int,
    val byteSize: Long,
    val manifest: List<Long>?,
    val wrappedBlobKey: String,
)

/**
 * What the application adds to a shared subtree beyond the nodes core itself owns (a share's guest resolve and its
 * blob GET are both answered from the subtree). Core knows the node tree, the live-node predicate and the cover blob;
 * everything else that hangs off a node — today the media and their derivatives — is an application's, contributed here.
 *
 * Both methods receive `liveNodeIds`, the subtree's ids already filtered by core's `deletedAt is null` predicate, and
 * must carry their OWN live predicate for their rows (a trashed medium is a soft delete): the listing (`rows`) and the
 * blob authorization (`authorizedBlobIds`) are two reads on purpose, so that one covering for the other cannot reopen
 * a hole silently (ADR 0061; `ShareTrashedMediaSliceTest` bites each on its own). Contributions are merged without order.
 */
interface SharedSubtreeContributor {
    /** The rows to serve for the live nodes of [scopeId]'s shared subtree. */
    fun rows(scopeId: String, liveNodeIds: List<String>): List<ShareMediaRow>

    /** Every blob id this contributor attaches under the same nodes — the set a blob GET is authorized against. */
    fun authorizedBlobIds(scopeId: String, liveNodeIds: List<String>): Set<String>
}
