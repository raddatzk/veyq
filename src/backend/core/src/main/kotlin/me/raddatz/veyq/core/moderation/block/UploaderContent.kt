package me.raddatz.veyq.core.moderation.block

/** An uploader as attribution names it: a member's account ([guest] false) or a link's guest identity. */
data class Uploader(val id: String, val guest: Boolean)

/** What one uploader uploaded to a space: nodes whose content they put there (a Depot file), and an application's items. */
data class UploadedItems(val nodeIds: Set<String>, val itemIds: Set<String>) {
    val size: Int get() = nodeIds.size + itemIds.size

    operator fun plus(other: UploadedItems) = UploadedItems(nodeIds + other.nodeIds, itemIds + other.itemIds)

    companion object {
        val NONE = UploadedItems(emptySet(), emptySet())
    }
}

/**
 * The seam "everything from this uploader" reads (ADR 0093 §§ 1, 6; a moderator's `UPLOADER_CONTENT` step): each
 * application answers for its own uploads, by the clear attribution it already stores (ADR 0079: core names no
 * application table, nor an application's slot). Memories: the media whose uploader it is; Depot: the files whose current
 * content it uploaded. **What counts is an upload of content**, never a node's look — a cover or a banner someone set is
 * the node's, and blocking it would block the node it decorates — and live and trashed alike, since a trashed item still
 * stands until its purge. Read in the caller's transaction; it writes nothing.
 */
interface UploaderContent {
    fun of(scopeId: String, uploader: Uploader): UploadedItems
}
