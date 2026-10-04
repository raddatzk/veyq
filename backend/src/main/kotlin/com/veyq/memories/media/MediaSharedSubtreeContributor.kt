package com.veyq.memories.media

import com.veyq.core.blob.BlobEntity
import com.veyq.core.sharing.ShareDerivativeRow
import com.veyq.core.sharing.ShareMediaRow
import com.veyq.core.sharing.SharedSubtreeContributor
import jakarta.enterprise.context.ApplicationScoped

/** The media half of a shared subtree: live media originals + their derivatives (the cover arm is core's). */
@ApplicationScoped
class MediaSharedSubtreeContributor : SharedSubtreeContributor {

    override fun rows(scopeId: String, liveNodeIds: List<String>): List<ShareMediaRow> {
        // `deletedAt is null` — a trashed medium (uc.memory.delete-media, cr-0110) is a soft-delete, so without
        // this predicate the guest paths keep serving it for the whole retention window. Both share reads must
        // carry it: this listing and [authorizedBlobIds], which authorizes the blob GET.
        return MediaEntity.find("scopeId = ?1 and nodeId in ?2 and deletedAt is null", scopeId, liveNodeIds).list().mapNotNull { m ->
            val blob = BlobEntity.findById(m.blobId) ?: return@mapNotNull null
            val derivatives = MediaDerivativeEntity.find("mediaId = ?1", m.id).list().mapNotNull { d ->
                val derivBlob = BlobEntity.findById(d.blobId) ?: return@mapNotNull null
                ShareDerivativeRow(d.kind, derivBlob.id, derivBlob.chunkCount, derivBlob.byteSize, decodeManifest(derivBlob.manifest), derivBlob.wrappedBlobKey)
            }
            ShareMediaRow(m.nodeId, m.id, blob.id, blob.chunkCount, blob.byteSize, decodeManifest(blob.manifest), blob.wrappedBlobKey, derivatives, m.wrappedMeta, m.shareIdentityId)
        }
    }

    override fun authorizedBlobIds(scopeId: String, liveNodeIds: List<String>): Set<String> {
        val ids = HashSet<String>()
        // Trashed media are excluded here too — else the listing hides a medium the blob GET still presigns.
        val media = MediaEntity.find("scopeId = ?1 and nodeId in ?2 and deletedAt is null", scopeId, liveNodeIds).list()
        for (m in media) {
            ids.add(m.blobId)
            MediaDerivativeEntity.find("mediaId = ?1", m.id).list().forEach { ids.add(it.blobId) }
        }
        return ids
    }

    // Manifest = a JSON array of per-chunk ciphertext sizes (mirrors MediaStore's hand-rolled encoding).
    private fun decodeManifest(s: String?): List<Long>? =
        s?.trim()?.removeSurrounding("[", "]")?.takeIf { it.isNotBlank() }?.split(",")?.map { it.trim().toLong() }
}
