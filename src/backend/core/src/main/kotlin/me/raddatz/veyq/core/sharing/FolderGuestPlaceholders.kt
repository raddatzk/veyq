package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.blob.NodeAttachmentEntity
import me.raddatz.veyq.core.node.NodeEntity
import jakarta.enterprise.context.ApplicationScoped

/**
 * Which of core's own `FOLDER`s are an empty upload placeholder a guest may take back ([GuestPlaceholders],
 * `abandonShareNode`): the folders a guest's folder-tree upload created before every file of it was refused (a full
 * link) or given up. Core owns the kind, so core declares it — no application ever does (the partition). Empty is
 * what core itself could still lose: no attachment in any slot (a cover is one) and no sealed metadata; the child and
 * the open upload are `ShareNodesHandler.abandon`'s own checks, made before this one.
 *
 * A member's empty folder is not one — core asks first whose name it is (the calling identity must have written it
 * last, ADR 0093 § 8) — unless a `READ_WRITE` guest moved it, which writes its name: then all the guest can remove is a
 * name with nothing behind it, which it could already rename away (ADR 0080 decision 6).
 */
@ApplicationScoped
class FolderGuestPlaceholders : GuestPlaceholders {
    override fun handles(kind: String): Boolean = kind == NodeEntity.FOLDER

    override fun isEmpty(scopeId: String, nodeId: String): Boolean =
        NodeEntity.count("scopeId = ?1 and id = ?2 and wrappedMeta is null", scopeId, nodeId) == 1L &&
            NodeAttachmentEntity.count("scopeId = ?1 and nodeId = ?2", scopeId, nodeId) == 0L
}
