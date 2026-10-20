package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.generated.model.PublicShare
import java.security.MessageDigest

/**
 * The guest resolve's `ETag` (ADR 0081): a digest of the unlocked [PublicShare] exactly as served, and of the
 * attachments on its nodes (a file's blob or a cover, which a guest reads beside them but the body does not carry, so
 * that an upload completing moves the tag) — every field of the body, since
 * the generated model's `toString` names them all, so a field added later is in the tag without anyone remembering to
 * add it. Nothing in it is outside the response, so a guest learns nothing from the tag that the body does not say; in
 * particular it does NOT follow the scope's tree revision, which moves on every change in the space, shared subtree or
 * not (the member listing's ETag may do that; a guest must not learn that something outside its link changed).
 *
 * The caller hands over the nodes in a fixed order (the store's row order is no part of the content). Truncated
 * SHA-256, 128 bits: a tag, not a MAC — equality with the guest's last tag is the only question it answers.
 */
object ShareETag {
    fun of(unlocked: PublicShare, attachments: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256").digest((unlocked.toString() + "\n" + attachments.joinToString("\n")).toByteArray(Charsets.UTF_8))
        return "\"" + digest.take(16).joinToString("") { "%02x".format(it) } + "\""
    }

    /** Whether the guest's `If-None-Match` names [etag] — lenient about a weak marker, never about the value. */
    fun matches(ifNoneMatch: String?, etag: String): Boolean =
        ifNoneMatch?.trim()?.removePrefix("W/")?.trim() == etag
}
