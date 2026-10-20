package me.raddatz.veyq.core.content

/**
 * The node listing's `ETag` (ADR 0081): the scope's tree revision, quoted — the one form `listNodes` sends and reads
 * back. Lenient on the way in, as Memories' media listing is: a weak marker and the quotes are stripped, and anything
 * that is no number is "no match" (null ⇒ a plain `200`), never an error.
 */
object TreeETag {
    fun of(revision: Long): String = "\"$revision\""

    fun parse(raw: String?): Long? =
        raw?.trim()?.removePrefix("W/")?.trim()?.trim('"')?.toLongOrNull()?.takeIf { it >= 0 }
}
