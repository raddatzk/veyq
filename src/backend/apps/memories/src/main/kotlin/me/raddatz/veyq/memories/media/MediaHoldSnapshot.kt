package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.moderation.hold.HoldSnapshot
import me.raddatz.veyq.core.moderation.hold.HoldSnapshotContributor
import me.raddatz.veyq.core.moderation.hold.HoldTarget
import jakarta.enterprise.context.ApplicationScoped

/**
 * Memories' rows of a hold (en.moderation.hold): the media of a held subtree — trashed ones too, they are still the
 * Memory's until purged — or the one reported medium, or an uploader's media (a moderator's `UPLOADER_CONTENT` step),
 * each with the bucket it sits in. A reported name holds none. A medium's original and derivatives are core's blob
 * service's rows held by it, which the service's own contributor copies with their blob rows by node, by item and by
 * owner (ADR 0113, phase 160); every guest identity these rows name is copied by core.
 */
@ApplicationScoped
class MediaHoldSnapshot : HoldSnapshotContributor {
    override val phase = 100

    override fun contribute(target: HoldTarget, snapshot: HoldSnapshot) {
        val media = when (target) {
            is HoldTarget.Subtree -> snapshot.copy("veyq_media", "node_id", target.nodeIds)
            is HoldTarget.Item -> snapshot.copy("veyq_media", "id", listOf(target.itemId))
            is HoldTarget.Uploads -> snapshot.copy("veyq_media", "id", target.itemIds)
            is HoldTarget.Link, is HoldTarget.Name -> return
        }
        media.forEach { m ->
            snapshot.identity(m["share_identity_id"])
            snapshot.identity(m["meta_editor_identity_id"])
        }
        val buckets = when (target) {
            is HoldTarget.Subtree -> snapshot.copy("veyq_bucket", "node_id", target.nodeIds)
            else -> snapshot.copy("veyq_bucket", "id", media.mapNotNull { it["bucket_id"] })
        }
        buckets.forEach { snapshot.identity(it["name_editor_identity_id"]) }
    }
}
