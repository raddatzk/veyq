package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.sharing.SharedSubtreeContributor
import jakarta.enterprise.context.ApplicationScoped

/**
 * The media half of what a share's blob GET is authorized against: live media originals + their derivatives (the
 * cover arm is core's). The listing a guest is served is [MediaStore.listShareMedia], a separate read with its own
 * live predicate (see [SharedSubtreeContributor]).
 */
@ApplicationScoped
class MediaSharedSubtreeContributor : SharedSubtreeContributor {

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

    /** The set's predicate for one blob, by two counts instead of a row per medium: a live medium's original, or a tier of one. */
    override fun holds(scopeId: String, liveNodeId: String, blobId: String): Boolean =
        MediaEntity.count("scopeId = ?1 and nodeId = ?2 and deletedAt is null and blobId = ?3", scopeId, liveNodeId, blobId) > 0 ||
            MediaDerivativeEntity.getEntityManager()
                .createQuery(
                    "select count(d) from MediaDerivativeEntity d, MediaEntity m where d.blobId = ?1 and m.id = d.mediaId " +
                        "and m.scopeId = ?2 and m.nodeId = ?3 and m.deletedAt is null",
                    java.lang.Long::class.java,
                )
                .setParameter(1, blobId).setParameter(2, scopeId).setParameter(3, liveNodeId)
                .singleResult.toLong() > 0
}
