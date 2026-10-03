package com.veyq.app.features.content.itemfavorites

import com.veyq.app.core.node.NodeSubtreeEraser
import jakarta.enterprise.context.ApplicationScoped

/**
 * Every member's item-favorite blob for the erased nodes. Keyed on the node, not the scope: the scope survives this
 * purge. Dropped only HERE and not at soft-delete — a restore inside the window must hand back the Memory with its
 * marks intact. Phase 610: after core's cover blobs, as the purge always ran it; it references no guest identity.
 */
@ApplicationScoped
class ItemFavoritesNodeSubtreeEraser : NodeSubtreeEraser {
    override val phase = 610

    override fun erase(scopeId: String, nodeIds: List<String>): Set<String> {
        ItemFavoritesEntity.delete("nodeId in ?1", nodeIds)
        return emptySet()
    }
}
