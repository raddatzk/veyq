package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.blob.MemberBlobRoles
import jakarta.enterprise.context.ApplicationScoped

/**
 * The roles a member uploads a Memories blob in, for core's member start (inbox #366): a medium's original and its
 * derivatives ([MediaUploadCompletion]) - [MemoryBlobRoles.MEMBER]. A node's cover role is core's (`CoreMemberBlobRoles`).
 */
@ApplicationScoped
class MemoriesMemberBlobRoles : MemberBlobRoles {
    override fun roles(): Set<String> = MemoryBlobRoles.MEMBER
}
