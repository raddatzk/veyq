package me.raddatz.veyq.core.content

import me.raddatz.veyq.core.blob.MemberBlobRoles
import jakarta.enterprise.context.ApplicationScoped

/**
 * A node's **cover** (inbox #411): one image attached to the node itself, in its `COVER` slot of core's node
 * attachments (ADR 0079 Consequences) - not a child, not a file in a folder. Core owns the slot and the two
 * operations that write it ([NodeCoverHandler]); which kinds take one is each kind's own rule
 * ([me.raddatz.veyq.core.node.NodeKindRules.takesCover]), so core names no application kind. The slot token is also the
 * upload's `BlobRole`: the client starts the upload as `COVER` owned by the node, and the blob's key wrap is sealed
 * bound to blob, node and role (ADR 0072) - so a cover blob can be attached to no other node or slot.
 */
object NodeCover {
    const val SLOT = "COVER"
}

/**
 * The one role core itself declares for a member's upload start ([MemberBlobRoles]): a `FOLDER` takes a cover with no
 * application at all, so core alone admits `COVER` (and refuses every other role - `CoreAloneSliceTest`).
 */
@ApplicationScoped
class CoreMemberBlobRoles : MemberBlobRoles {
    override fun roles(): Set<String> = setOf(NodeCover.SLOT)
}
