package me.raddatz.veyq.core.crypto

/**
 * The digest an account rotation's publish binds its staged self-grants by (cr-secure-the-account decision 3):
 * `sha256(utf8("veyq/v1/rotation-stage") || lp(utf8(scopeId)) || lp(u64be(epoch)) || lp(sha256(wrappedGrant)) …)` over
 * the entries in ascending `scopeId` order (canonical lowercase UUIDs, so text order is byte order), one per scope,
 * `wrappedGrant` DECODED. The client binds it into the rotation proof statement; the server recomputes it over the rows
 * it staged and refuses a publish naming another one, so the Secret Key's proof covers exactly what is applied.
 *
 * Pinned for both stacks by the `rotation_stage` vector (entries listed out of order, so the sort is load-bearing);
 * `RotationStageDigestTest` asserts this side. Auth crypto, not content crypto.
 */
object RotationStageDigest {

    /** One staged self-grant as the digest reads it: the scope, the epoch its key is of, its ciphertext (base64). */
    data class Entry(val scopeId: String, val epoch: Long, val wrappedGrant: String)

    private val DOMAIN = "veyq/v1/rotation-stage".toByteArray(Charsets.UTF_8)

    /** The digest, or null when an id is no canonical UUID, an epoch is negative, a ciphertext is no base64 or one
     *  scope is listed twice. */
    fun of(entries: Collection<Entry>): ByteArray? {
        val sorted = entries.sortedBy { it.scopeId }
        if (sorted.zipWithNext().any { (a, b) -> a.scopeId == b.scopeId }) return null
        var bytes = DOMAIN
        for (e in sorted) {
            if (e.epoch < 0) return null
            val id = TypedStatement.id(e.scopeId) ?: return null
            val grant = TypedStatement.decode(e.wrappedGrant) ?: return null
            bytes += TypedStatement.lp(id) + TypedStatement.lp(TypedStatement.u64(e.epoch)) + TypedStatement.lp(TypedStatement.sha256(grant))
        }
        return TypedStatement.sha256(bytes)
    }
}
