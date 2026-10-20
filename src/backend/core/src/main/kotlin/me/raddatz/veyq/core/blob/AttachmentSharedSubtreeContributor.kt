package me.raddatz.veyq.core.blob

import me.raddatz.veyq.core.sharing.SharedSubtreeContributor
import jakarta.enterprise.context.ApplicationScoped

/**
 * The attachment arm of what a share's blob GET is authorized against: every attachment blob on a live node of the
 * shared subtree. An attachment has no trash of its own — it lives and goes with its node — so core's live-node
 * filter ([liveNodeIds]) is its whole live predicate; an attachment on a trashed node is not served.
 */
@ApplicationScoped
class AttachmentSharedSubtreeContributor : SharedSubtreeContributor {

    override fun authorizedBlobIds(scopeId: String, liveNodeIds: List<String>): Set<String> {
        if (liveNodeIds.isEmpty()) return emptySet()
        return NodeAttachmentEntity.find("scopeId = ?1 and nodeId in ?2", scopeId, liveNodeIds).list()
            .mapTo(HashSet()) { it.blobId }
    }
}
