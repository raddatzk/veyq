package me.raddatz.veyq.mobile.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * A blob's sealed metadata (its type, the name it was uploaded under, its last modification), a port of
 * `src/packages/crypto/src/blobMeta.ts`: compact UTF-8 JSON in a fixed key order `{v, type, name?, modifiedAt?, sha256?}`,
 * padded with spaces to the next multiple of 256 bytes (the ciphertext tells only which of four buckets it fell into),
 * sealed under the blob's own key with the purpose-only `blob-meta` seal. Pinned by `v1.json#seal.sym['blob-meta']`.
 * Written once, at the upload's declare.
 */
object BlobMeta {
    const val BUCKET = 256
    const val MAX = 1024
    private const val TYPE_BYTES = 127
    private const val NAME_BYTES = 255
    private const val MAX_SAFE = 9_007_199_254_740_991L
    private val SHA256_B64URL = Regex("^[A-Za-z0-9_-]{43}$")

    /** What a reader gets back: the type ('' when unknown), and the name and modification time when present. */
    data class Opened(val v: Long, val type: String, val name: String?, val modifiedAt: Long?)

    private fun isControl(c: Int) = c in 0x00..0x1f || c in 0x7f..0x9f

    private fun codePoints(s: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            val n = if (s[i].isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) 2 else 1
            out += s.substring(i, i + n)
            i += n
        }
        return out
    }

    /** The longest prefix of [s] whose UTF-8 fits [max] bytes, cut between code points. */
    private fun clampUtf8(s: String, max: Int): String {
        var bytes = 0
        val out = StringBuilder()
        for (cp in codePoints(s)) {
            bytes += cp.encodeToByteArray().size
            if (bytes > max) break
            out.append(cp)
        }
        return out.toString()
    }

    /** A name as the layout stores it: no controls, NFC, trimmed, at most 255 UTF-8 bytes; '' when nothing is left. */
    fun normalizeName(name: String): String =
        clampUtf8(nfc(name.filterNot { isControl(it.code) }).trim(), NAME_BYTES).trimEnd()

    /** The padded plaintext. A type over 127 bytes or with a control character is written as ''; a negative or unsafe
     *  `modifiedAt` is left out, and so is a [sha256] that is not a 32-byte digest in base64url (a medium's original). */
    fun encode(type: String, name: String?, modifiedAt: Long?, sha256: String? = null): ByteArray {
        val t = if (type.none { isControl(it.code) } && type.encodeToByteArray().size <= TYPE_BYTES) type else ""
        val n = name?.let(::normalizeName).orEmpty()
        val doc = buildJsonObject {
            put("v", 1)
            put("type", t)
            if (n.isNotEmpty()) put("name", n)
            if (modifiedAt != null && modifiedAt in 0..MAX_SAFE) put("modifiedAt", modifiedAt)
            if (sha256 != null && SHA256_B64URL.matches(sha256)) put("sha256", sha256)
        }
        val body = doc.toString().encodeToByteArray()
        val out = ByteArray(((body.size + BUCKET - 1) / BUCKET) * BUCKET) { 0x20 }
        body.copyInto(out)
        return out
    }

    /** A plaintext read back, tolerant of what it does not know; null for what is no metadata document at all. */
    fun decode(plaintext: ByteArray): Opened? = runCatching {
        val o = Json.parseToJsonElement(plaintext.decodeToString(throwOnInvalidSequence = true)) as? JsonObject ?: return null
        val v = (o["v"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it in 1..MAX_SAFE } ?: return null
        fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val modified = (o["modifiedAt"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it in 0..MAX_SAFE }
        Opened(v, str("type").orEmpty(), str("name")?.takeIf { it.isNotEmpty() }, modified)
    }.getOrNull()

    fun seal(suite: V1, blobKey: ByteArray, type: String, name: String?, modifiedAt: Long?, sha256: String? = null): ByteArray =
        suite.seal(Context("blob-meta"), blobKey, encode(type, name, modifiedAt, sha256))

    /** The served meta opened under the blob's key; null when it is not this blob's or malformed ("no meta"). */
    fun open(suite: V1, blobKey: ByteArray, sealed: ByteArray): Opened? =
        runCatching { decode(suite.open(Context("blob-meta"), blobKey, sealed)) }.getOrNull()
}
