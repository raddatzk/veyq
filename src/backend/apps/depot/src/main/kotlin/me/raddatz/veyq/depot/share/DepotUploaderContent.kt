package me.raddatz.veyq.depot.share

import me.raddatz.veyq.core.blob.NodeAttachmentEntity
import me.raddatz.veyq.core.moderation.block.UploadedItems
import me.raddatz.veyq.core.moderation.block.Uploader
import me.raddatz.veyq.core.moderation.block.UploaderContent
import me.raddatz.veyq.depot.content.DepotBlobRoles
import jakarta.enterprise.context.ApplicationScoped

/**
 * Depot's half of [UploaderContent]: every file of the space whose current content the uploader put there (the
 * attachment's uploader, ADR 0083 decision 8 — a replace moves it to who replaced), live and trashed. A file is a node, so
 * a block of it is a node's block; a drop box or a guest's identity folder holds files but is no upload itself.
 */
@ApplicationScoped
class DepotUploaderContent : UploaderContent {
    override fun of(scopeId: String, uploader: Uploader): UploadedItems {
        val column = if (uploader.guest) "shareIdentityId" else "uploaderUserId"
        val nodes = NodeAttachmentEntity.list("scopeId = ?1 and slot = ?2 and $column = ?3", scopeId, DepotBlobRoles.FILE_CONTENT, uploader.id)
            .mapTo(LinkedHashSet()) { it.nodeId }
        return UploadedItems(nodes, emptySet())
    }
}
