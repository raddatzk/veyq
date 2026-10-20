package me.raddatz.veyq.memories.moderation

import me.raddatz.veyq.core.identity.PrincipalKey
import me.raddatz.veyq.core.moderation.block.UploadedItems
import me.raddatz.veyq.core.moderation.block.Uploader
import me.raddatz.veyq.core.moderation.block.UploaderContent
import me.raddatz.veyq.memories.media.MediaEntity
import jakarta.enterprise.context.ApplicationScoped

/**
 * Memories' half of [UploaderContent]: every medium of the space the uploader uploaded, live and trashed — by its owner
 * key (`created_by`, ADR 0034), and for a guest also by `share_identity_id`, the attribution a guest's upload carried
 * first (ADR 0030). A Memory's cover or banner is the Memory's look, not a medium, and is not among them.
 */
@ApplicationScoped
class MediaUploaderContent : UploaderContent {
    override fun of(scopeId: String, uploader: Uploader): UploadedItems {
        val key = (if (uploader.guest) PrincipalKey.Share(uploader.id) else PrincipalKey.User(uploader.id)).format()
        val ids = MediaEntity.list("scopeId = ?1 and createdBy = ?2", scopeId, key).mapTo(LinkedHashSet()) { it.id }
        if (uploader.guest) MediaEntity.list("scopeId = ?1 and shareIdentityId = ?2", scopeId, uploader.id).mapTo(ids) { it.id }
        return UploadedItems(emptySet(), ids)
    }
}
