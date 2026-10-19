package me.raddatz.veyq.core.sharing

/**
 * What the application attaches under a shared subtree beyond the nodes core itself owns, as far as a share's blob
 * GET must see it. Core knows the node tree, the live-node predicate and the cover blob; every other blob that hangs
 * off a node is contributed here — core's own attachments (`AttachmentSharedSubtreeContributor`) and an
 * application's rows (today Memories' media and their derivatives).
 *
 * Core serves no application rows to a guest: what an application lists under a share is its own guest read, behind
 * core's view gate ([ShareGuestGate.viewGate], ADR 0079 § Application guest operations). That listing and this
 * authorization are two reads on purpose, each with its OWN live predicate for its rows (a trashed medium is a soft
 * delete), so that one covering for the other cannot reopen a hole silently (ADR 0061; `ShareTrashedMediaSliceTest`
 * bites each on its own). Contributions are merged without order.
 */
interface SharedSubtreeContributor {
    /**
     * Every blob id this contributor attaches under [liveNodeIds] — the subtree's ids already filtered by core's
     * `deletedAt is null` predicate — in [scopeId]: the set a share's blob GET is authorized against.
     */
    fun authorizedBlobIds(scopeId: String, liveNodeIds: List<String>): Set<String>

    /**
     * Whether [blobId] is in [authorizedBlobIds] for the one live node [liveNodeId] - the question a shared blob row asks
     * of its source (`SharedBlobs`, ADR 0013 § 5). An arm with many rows per node answers it by
     * a targeted read; it must answer exactly as the set does (`SharedBlobSliceTest` holds each arm to that).
     */
    fun holds(scopeId: String, liveNodeId: String, blobId: String): Boolean = blobId in authorizedBlobIds(scopeId, listOf(liveNodeId))
}
