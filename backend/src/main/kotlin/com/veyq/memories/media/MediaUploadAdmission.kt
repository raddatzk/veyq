package com.veyq.memories.media

import com.veyq.core.node.NodeEntity
import com.veyq.core.quota.UploadAdmission
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager

/**
 * The Memories half of a link's usage (ADR 0073 §4): the blobs of every medium the link's identities contributed —
 * the medium's own blob and each derivative's. A medium a member uploaded into the same Memory is not the link's, and
 * neither is another link's. Core adds the link's open sessions (`StorageQuota.linkUsage`).
 */
@ApplicationScoped
class MediaUploadAdmission(private val em: EntityManager) : UploadAdmission {

    // MEMORY only, the one kind a share's root is here ([MediaShareGuestWrites.supports]).
    override fun supports(nodeKind: String): Boolean = nodeKind == NodeEntity.MEMORY

    override fun linkBlobUsage(shareId: String): Long = sum(
        """
        SELECT COALESCE((SELECT SUM(b.byte_size) FROM veyq_blob b WHERE b.id IN (
                   SELECT m.blob_id FROM veyq_media m
                     JOIN veyq_share_identity i ON i.id = m.share_identity_id
                    WHERE i.share_id = :key
                   UNION
                   SELECT d.blob_id FROM veyq_media_derivative d
                     JOIN veyq_media m ON m.id = d.media_id
                     JOIN veyq_share_identity i ON i.id = m.share_identity_id
                    WHERE i.share_id = :key)), 0)
        """,
        shareId,
    )

    private fun sum(sql: String, key: String): Long =
        (em.createNativeQuery(sql.trimIndent()).setParameter("key", key).singleResult as Number).toLong()
}
