package me.raddatz.veyq.mobile.blob

/**
 * The name a download is saved under (core's `core/blob/meta.ts` `downloadName`): the first of [names] that is not blank
 * (trimmed) — a file's node name, then its sealed uploaded name —, else [fallback], gaining the extension the sealed [type]
 * names only where it has none of its own; an existing extension is never replaced.
 */
object DownloadName {
    private val HAS_EXTENSION = Regex("[^.]\\.[^.\\s]{1,16}$")

    /** Core's `MIME_TO_EXT`, exactly: a type it does not name adds nothing. */
    private val MIME_TO_EXT = mapOf(
        "image/jpeg" to "jpg", "image/png" to "png", "image/gif" to "gif", "image/webp" to "webp", "image/heic" to "heic",
        "application/pdf" to "pdf", "video/mp4" to "mp4", "video/quicktime" to "mov", "video/webm" to "webm", "video/x-matroska" to "mkv",
    )

    /** The extension core names for [type], or null for one it does not name. */
    fun extensionOf(type: String?): String? = MIME_TO_EXT[type.orEmpty()]

    fun of(type: String?, fallback: String, vararg names: String?): String {
        val base = names.firstNotNullOfOrNull { it?.trim()?.takeIf { n -> n.isNotEmpty() } } ?: fallback
        val ext = MIME_TO_EXT[type.orEmpty()] ?: return base
        return if (HAS_EXTENSION.containsMatchIn(base)) base else "$base.$ext"
    }
}
