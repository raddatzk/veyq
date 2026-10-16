package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.identity.PrincipalKey
import me.raddatz.veyq.core.sharing.GuestIdentityUsage
import jakarta.enterprise.context.ApplicationScoped

/** A guest identity is still in use while a medium (live or trashed) or a bucket names it — as its uploader or owner, or
 *  as the last editor of its meta or name (ADR 0093 § 8). */
@ApplicationScoped
class MediaGuestIdentityUsage : GuestIdentityUsage {
    override fun stillReferenced(identityId: String): Boolean =
        MediaEntity.count("shareIdentityId = ?1 or metaEditorIdentityId = ?1", identityId) > 0 ||
            BucketEntity.count("ownerKey = ?1 or nameEditorIdentityId = ?2", PrincipalKey.Share(identityId).format(), identityId) > 0
}
