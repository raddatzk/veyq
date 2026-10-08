package me.raddatz.veyq.memories.media

/**
 * Memories' blob roles — its values of the contract's open `BlobRole` token (cr-files-application P3-S2). Core stores
 * a role and compares it ([me.raddatz.veyq.core.blob.UploadSessionEntity.boundTo]) but never names one; each role a
 * Memories blob is attached in is named here, and the `K_blob` wrap is sealed bound to it (ADR 0072).
 */
object MemoryBlobRoles {
    /** A medium's original bytes. */
    const val ORIGINAL = "ORIGINAL"

    /** A medium's grid derivative. */
    const val THUMBNAIL = "THUMBNAIL"

    /** A medium's lightbox derivative. */
    const val PREVIEW = "PREVIEW"

    /** A node's standalone cover (`cover_blob_id`, cr-0073). */
    const val COVER = "COVER"

    /** The roles a medium's derivative is attached in — the values of the media contract's `MediaDerivativeKind`. */
    val DERIVATIVES: Set<String> = setOf(THUMBNAIL, PREVIEW)

    /**
     * The roles a guest of a share uploads in: a medium and its derivatives, never a [COVER] — the cover is a member's
     * write (`commitCover`). The contract's closed enum said this until the role opened to a token.
     */
    val GUEST: Set<String> = setOf(ORIGINAL) + DERIVATIVES

    /** The roles a member uploads in: every role above — a guest's and the [COVER] ([MemoriesMemberBlobRoles]). */
    val MEMBER: Set<String> = GUEST + COVER
}
