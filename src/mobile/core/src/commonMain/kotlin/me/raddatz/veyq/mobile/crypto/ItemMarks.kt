package me.raddatz.veyq.mobile.crypto

/**
 * An item mark's self-tag and plaintext (ADR 0068 §4 and §9, inbox #500), a port of `v1.ts`'s item-mark half. Which entry
 * of a node's marks is the caller's own is a claim of the server's; the tag inside the plaintext is what the caller
 * checks it against: `HMAC-SHA256(HKDF(masterKey, "", "veyq/v1/item-mark-self"), statement)`, the `item-mark-self`
 * statement binding the scope, the node and the marked SET. The plaintext is `tag ‖ id…`, each distinct id's 16 bytes in
 * ascending order, one encoding per set. Pinned by `v1.json#item_mark_self` and `#seal.sym.item-favorites`.
 */
class ItemMarks(private val suite: V1) {
    private val p = suite.p

    /** The caller's self-tag key, derived from the Master Key. The caller zeroes it. */
    fun selfKey(masterKey: ByteArray): ByteArray = p.hkdfSha256(masterKey, ByteArray(0), utf8("veyq/v1/item-mark-self"), 32)

    /** SHA-256 over `lp(utf8(id))…` of the ids, de-duplicated and sorted: the set, however it was listed. */
    fun marksDigest(ids: Collection<String>): ByteArray = p.sha256(concat(*ids.toSet().sorted().map { lp(utf8(it)) }.toTypedArray()))

    /** The `item-mark-self` statement over ([scopeId], [nodeId], [ids]). */
    fun statement(scopeId: String, nodeId: String, ids: Collection<String>): ByteArray =
        encodeContext(Purposes.STATEMENT, Context("item-mark-self", "scopeId" to scopeId, "nodeId" to nodeId, "marks" to marksDigest(ids)))

    fun selfTag(key: ByteArray, scopeId: String, nodeId: String, ids: Collection<String>): ByteArray =
        p.hmacSha256(key, statement(scopeId, nodeId, ids))

    /** Whether [tag] is the self-tag of the set under [key]; constant-time over the tag bytes, false for anything malformed. */
    fun selfTagVerifies(key: ByteArray, scopeId: String, nodeId: String, ids: Collection<String>, tag: ByteArray): Boolean {
        val expected = runCatching { selfTag(key, scopeId, nodeId, ids) }.getOrNull() ?: return false
        if (expected.size != tag.size) return false
        var diff = 0
        for (i in expected.indices) diff = diff or (expected[i].toInt() xor tag[i].toInt())
        return diff == 0
    }

    /** `tag ‖ id…`: each distinct id's 16 bytes ascending. Canonical lowercase UUIDs only. */
    fun encode(tag: ByteArray, ids: Collection<String>): ByteArray {
        if (tag.size != TAG_BYTES) throw IllegalArgumentException("item-mark tag is not 32 bytes")
        val sorted = ids.toSet().sorted()
        val out = ByteArray(TAG_BYTES + ID_BYTES * sorted.size)
        tag.copyInto(out)
        sorted.forEachIndexed { i, id ->
            if (!CANONICAL_UUID.matches(id)) throw IllegalArgumentException("item-mark id is not a canonical lowercase UUID")
            unhex(id.replace("-", "")).copyInto(out, TAG_BYTES + ID_BYTES * i)
        }
        return out
    }

    /** The tag and the ids of a plaintext, or null for one no encoder writes (cut short, ids not distinct and ascending). */
    fun decode(plaintext: ByteArray): Pair<ByteArray, List<String>>? {
        val body = plaintext.size - TAG_BYTES
        if (body < 0 || body % ID_BYTES != 0) return null
        val ids = ArrayList<String>()
        var previous = ""
        var at = TAG_BYTES
        while (at < plaintext.size) {
            val h = hex(plaintext.copyOfRange(at, at + ID_BYTES))
            val id = "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
            if (previous >= id) return null
            ids += id
            previous = id
            at += ID_BYTES
        }
        return plaintext.copyOfRange(0, TAG_BYTES) to ids
    }

    private companion object {
        const val TAG_BYTES = 32
        const val ID_BYTES = 16
        val CANONICAL_UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }
}
