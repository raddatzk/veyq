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

    /** A Memory's banner image or video (uc.memory.banner, ADR 0102): the node's own attachment, never a medium. */
    const val BANNER = "BANNER"

    /** A video banner's poster, its first frame (ADR 0102): the node's own attachment, never a medium. */
    const val BANNER_POSTER = "BANNER_POSTER"

    /** The roles a medium's derivative is attached in — the values of the media contract's `MediaDerivativeKind`. */
    val DERIVATIVES: Set<String> = setOf(THUMBNAIL, PREVIEW)

    /** The banner's two slots, each also the role its upload is started in ([me.raddatz.veyq.memories.banner.MemoryBannerHandler]). */
    val BANNER_ROLES: Set<String> = setOf(BANNER, BANNER_POSTER)

    /**
     * The roles a guest of a share uploads in: a medium and its derivatives. Never a cover - `COVER` is core's role
     * (`NodeCover`, declared by `CoreMemberBlobRoles`) and a member's write - and never a banner's: no guest operation
     * sets either (Memories' role matrix, `docs/apps/memories.md`).
     */
    val GUEST: Set<String> = setOf(ORIGINAL) + DERIVATIVES

    /**
     * The roles a member uploads a Memories blob in ([MemoriesMemberBlobRoles]): a guest's, and the banner's two. The
     * two sets differ only by [BANNER_ROLES]; `MediaUploadCompletion` still attaches a medium in [ORIGINAL] and
     * [DERIVATIVES] alone, so a banner upload completed as a medium is `upload_mismatch`.
     */
    val MEMBER: Set<String> = GUEST + BANNER_ROLES
}
