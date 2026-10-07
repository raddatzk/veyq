package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.quota.UploadAdmission
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager

/**
 * The Memories half of a link's usage (ADR 0073 §4): the blobs of every medium the link's identities contributed —
 * the medium's own blob and each derivative's. A medium a member uploaded into the same Memory or chapter is not the link's, and
 * neither is another link's. Core adds the link's open sessions and its node attachments' blobs (`StorageQuota.linkUsage`);
 * a cover is a member's, never a guest's, and counts in neither.
 */
@ApplicationScoped
class MediaUploadAdmission(private val em: EntityManager) : UploadAdmission {

    // The kinds a guest uploads into, as [MediaShareGuestWrites.supports] answers them. The measure itself is by
    // identity, so it counts the link's media in every Memory or chapter of its subtree, not only the root's.
    override fun supports(nodeKind: String): Boolean = nodeKind in MemoriesNodeKinds.SHARE_ROOTS

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
