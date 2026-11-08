package me.raddatz.veyq.mobile.crypto

/**
 * The one fixed-length pad of a sealed plaintext, a port of `src/packages/crypto/src/pad.ts`: `u32be(len) ‖ bytes ‖ zeros`,
 * so a ciphertext's length says which size class its plaintext was padded to and nothing more. Pinned by
 * `v1.json#field_seal` (`expense-entry`'s plaintext is a padded revision).
 */
object Pad {
    const val HEADER = 4

    /** A pad that cannot be made (`pad_too_large`) or a padded plaintext that is not one (`pad_malformed`). */
    class Invalid(val code: String) : Exception(code)

    fun padTo(bytes: ByteArray, paddedLen: Int): ByteArray {
        if (bytes.size > paddedLen - HEADER) throw Invalid("pad_too_large")
        val out = ByteArray(paddedLen)
        u32be(bytes.size).copyInto(out)
        bytes.copyInto(out, HEADER)
        return out
    }

    /** Pads to the smallest of [classes] (ascending padded lengths) that carries [bytes]; `pad_too_large` past the last. */
    fun padToClass(bytes: ByteArray, classes: List<Int>): ByteArray {
        require(classes.zipWithNext().all { (a, b) -> b > a }) { "size classes are not ascending" }
        val fit = classes.firstOrNull { bytes.size <= it - HEADER } ?: throw Invalid("pad_too_large")
        return padTo(bytes, fit)
    }

    /** The bytes a padded plaintext carries; `pad_malformed` on a header past the pad or a fill that is not all zeros. */
    fun unpad(padded: ByteArray): ByteArray {
        if (padded.size < HEADER) throw Invalid("pad_malformed")
        val len = readU32be(padded, 0)
        if (len < 0 || len > padded.size - HEADER) throw Invalid("pad_malformed")
        val end = HEADER + len
        for (i in end until padded.size) if (padded[i] != 0.toByte()) throw Invalid("pad_malformed")
        return padded.copyOfRange(HEADER, end)
    }
}

/** Base64url without padding (the wire's digests of Expenses revisions). */
fun b64url(bytes: ByteArray): String = b64(bytes).replace('+', '-').replace('/', '_').trimEnd('=')
