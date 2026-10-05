package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.sharing.GuestIdentityUsage
import jakarta.enterprise.context.ApplicationScoped

/** A guest identity is still in use while a medium (live or trashed) or a bucket names it. */
@ApplicationScoped
class MediaGuestIdentityUsage : GuestIdentityUsage {
    override fun stillReferenced(identityId: String): Boolean =
        MediaEntity.count("shareIdentityId", identityId) > 0 ||
            BucketEntity.count("ownerKey", OwnerKey.Share(identityId).format()) > 0
}
