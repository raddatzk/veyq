package me.raddatz.veyq.mobile.account

/**
 * SHA-1 (FIPS 180-4 § 6.1), one-shot, for the breached-password check alone ([BreachCheck], ADR 0151 decision 5): the
 * range service keys passwords on SHA-1, and libsodium has none. No suite primitive and no vector — nothing veyq seals,
 * signs or derives uses it; `Sha1Test` holds it to FIPS 180-4's examples and the web test's oracle. The `export/Sha256.kt`
 * form, with no dependency.
 */
internal object Sha1 {
    fun of(bytes: ByteArray): ByteArray {
        val h = intArrayOf(0x67452301, -0x10325477, -0x67452302, 0x10325476, -0x3c2d1e10)
        val bits = bytes.size.toLong() * 8
        val padded = ByteArray(((bytes.size + 8) / 64 + 1) * 64)
        bytes.copyInto(padded)
        padded[bytes.size] = 0x80.toByte()
        for (i in 0 until 8) padded[padded.size - 1 - i] = (bits ushr (8 * i)).toByte()
        val w = IntArray(80)
        for (block in padded.indices step 64) {
            for (t in 0 until 16) {
                val at = block + 4 * t
                w[t] = ((padded[at].toInt() and 0xff) shl 24) or ((padded[at + 1].toInt() and 0xff) shl 16) or
                    ((padded[at + 2].toInt() and 0xff) shl 8) or (padded[at + 3].toInt() and 0xff)
            }
            for (t in 16 until 80) w[t] = (w[t - 3] xor w[t - 8] xor w[t - 14] xor w[t - 16]).rotateLeft(1)
            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]; var e = h[4]
            for (t in 0 until 80) {
                val (f, k) = when {
                    t < 20 -> ((b and c) or (b.inv() and d)) to 0x5a827999
                    t < 40 -> (b xor c xor d) to 0x6ed9eba1
                    t < 60 -> ((b and c) or (b and d) or (c and d)) to -0x70e44324
                    else -> (b xor c xor d) to -0x359d3e2a
                }
                val temp = a.rotateLeft(5) + f + e + k + w[t]
                e = d; d = c; c = b.rotateLeft(30); b = a; a = temp
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e
        }
        padded.fill(0)
        w.fill(0)
        val out = ByteArray(20)
        for (i in 0 until 5) for (j in 0 until 4) out[4 * i + j] = (h[i] ushr (24 - 8 * j)).toByte()
        return out
    }

    /** The digest of [text]'s UTF-8 as upper-case hex — the web's `sha1HexUpper`. */
    fun hexUpper(text: String): String {
        val utf8 = text.encodeToByteArray()
        val digest = of(utf8)
        utf8.fill(0)
        return buildString { digest.forEach { append(HEX[(it.toInt() shr 4) and 0xf]).append(HEX[it.toInt() and 0xf]) } }.also { digest.fill(0) }
    }

    private const val HEX = "0123456789ABCDEF"
}
