package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.applicationblob.ApplicationBlobOwners
import me.raddatz.veyq.core.applicationblob.ApplicationBlobRole
import me.raddatz.veyq.core.applicationblob.OwnerAdmission
import jakarta.enterprise.context.ApplicationScoped

/**
 * Memories' half of core's blob service (ADR 0113): a medium's original and its derivative tiers are the service's blobs,
 * each held by the medium as its owner in its role — one per role and medium, and a link guest adds all three, as a
 * medium is a guest's upload too. Each role is **its owner's content** ([ApplicationBlobRole.ownItem] false): its
 * `K_blob` wrap is bound to the medium (`blob-key`'s `ownerId` = the media id, as it always was), it is completed only in
 * the medium's own completion ([MediaStore.attach], through `ApplicationBlobs.attachIn`), reported, blocked and held with
 * the medium, out of a guest's reach once the medium is trashed, and served by Memories' own media reads. Memories'
 * every write that changes what a medium keeps tells the service: a trash releases its blobs with it, a restore keeps
 * them, an erase of the medium erases them (`ApplicationBlobErasure.eraseOwners`).
 *
 * A Memory's banner and every cover are core's node attachments, not these (ADR 0113 decision 10).
 */
@ApplicationScoped
class MemoriesMediaBlobs : ApplicationBlobOwners {
    override fun accepts(nodeKind: String): Boolean = MediaUploadCompletion.attachesMedia(nodeKind)

    override fun roles(): Map<String, ApplicationBlobRole> = ROLES

    /** Never asked: the service's own completion takes no owner's content, so no blob of a medium reaches it. */
    override fun admit(scopeId: String, nodeId: String, ownerId: String, blobRowId: String): OwnerAdmission = OwnerAdmission.Taken

    companion object {
        /** One blob per role and medium; a guest adds every one of them (a medium is a guest's upload, ADR 0030). */
        val ROLES: Map<String, ApplicationBlobRole> = MemoryBlobRoles.GUEST.associateWith {
            ApplicationBlobRole(maxPerOwner = 1, guestMayAdd = true, ownItem = false)
        }
    }
}
