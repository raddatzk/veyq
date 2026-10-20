package me.raddatz.veyq.depot.content

import me.raddatz.veyq.core.blob.NodeAttachmentEntity
import me.raddatz.veyq.core.sharing.GuestPlaceholders
import me.raddatz.veyq.depot.DepotNodeKinds
import jakarta.enterprise.context.ApplicationScoped

/**
 * Which of Depot's nodes are an empty upload placeholder a guest may take back (core's [GuestPlaceholders],
 * `abandonShareNode`): a `FILE` whose slot never held a blob — the node a guest's upload created before the link
 * refused the bytes (`quota_exceeded`) or the guest gave up. Nothing ever empties a file's slot again (a replace swaps
 * the blob in place, `AttachmentStore.attach`), so "no `FILE_CONTENT` row" is "never held a file". A drop box is never
 * one: it is the owner's link-bearing node.
 */
@ApplicationScoped
class DepotGuestPlaceholders : GuestPlaceholders {
    override fun handles(kind: String): Boolean = kind == DepotNodeKinds.FILE

    override fun isEmpty(scopeId: String, nodeId: String): Boolean =
        NodeAttachmentEntity.count("scopeId = ?1 and nodeId = ?2 and slot = ?3", scopeId, nodeId, DepotBlobRoles.FILE_CONTENT) == 0L
}
