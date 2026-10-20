package me.raddatz.veyq.core.blob

import me.raddatz.veyq.core.sharing.GuestIdentityUsage
import jakarta.enterprise.context.ApplicationScoped

/**
 * A guest identity is still in use while an attachment names it as its uploader (the per-link byte limit counts by
 * it, and `fk_attachment_share_identity` would refuse its delete). Core's own usage — which means the collection
 * [GuestIdentityUsage.unreferenced] combines is never empty any more; its KDoc says what that costs.
 */
@ApplicationScoped
class AttachmentGuestIdentityUsage : GuestIdentityUsage {
    override fun stillReferenced(identityId: String): Boolean =
        NodeAttachmentEntity.count("shareIdentityId", identityId) > 0
}
