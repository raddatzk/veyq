package me.raddatz.veyq.core.moderation.hold

import jakarta.enterprise.context.ApplicationScoped

/**
 * Core's rows of a hold (en.moderation.hold § The snapshot): a subtree's node rows and their attachments (a cover, a
 * file's content — Depot owns no table, its files are core's attachments), and a link's share row. The blobs and guest
 * identities they name go to the snapshot, which [Holds] copies and pins after every contributor.
 */
@ApplicationScoped
class CoreHoldSnapshot : HoldSnapshotContributor {
    override val phase = 200

    override fun contribute(target: HoldTarget, snapshot: HoldSnapshot) {
        when (target) {
            is HoldTarget.Subtree -> {
                snapshot.copy("veyq_node", "id", target.nodeIds).forEach { node ->
                    snapshot.identity(node["share_identity_id"])
                    snapshot.identity(node["name_editor_identity_id"])
                    snapshot.identity(node["meta_editor_identity_id"])
                }
                snapshot.copy("veyq_node_attachment", "node_id", target.nodeIds).forEach { attachment ->
                    snapshot.blob(attachment["blob_id"])
                    snapshot.identity(attachment["share_identity_id"])
                }
                // The links rooted in the subtree: a guest's report of a link's root hands over the link's name key
                // (`LINK_NAME`), which opens the held link's name, not the node's (uc.moderation.review-a-case step 4).
                snapshot.copy("veyq_share", "node_id", target.nodeIds)
            }
            is HoldTarget.Link -> snapshot.copy("veyq_share", "id", listOf(target.shareId))
            is HoldTarget.Item -> {} // an application's item: its owner copies it
        }
    }
}
