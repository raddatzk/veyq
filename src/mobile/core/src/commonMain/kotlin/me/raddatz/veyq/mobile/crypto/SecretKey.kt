package me.raddatz.veyq.mobile.crypto

/**
 * The Secret Key's text form (a port of `src/packages/crypto/src/secretKey.ts`): Crockford base32, 52 symbols for the
 * 32 bytes and 2 check symbols (the first 10 bits of its SHA-256), 9 groups of 6 joined by `-`.
 */
object SecretKeyText {
    private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val BODY = 52
    private const val SYMBOLS = 54

    private fun bitsOf(bytes: ByteArray): List<Int> = bytes.flatMap { b -> (7 downTo 0).map { (b.toInt() shr it) and 1 } }

    private fun symbols(bits: List<Int>, count: Int): String = buildString {
        for (i in 0 until count) {
            var v = 0
            for (j in 0 until 5) v = (v shl 1) or (bits.getOrElse(i * 5 + j) { 0 })
            append(CROCKFORD[v])
        }
    }

    private fun canonical(p: Primitives, key: ByteArray) = symbols(bitsOf(key), BODY) + symbols(bitsOf(p.sha256(key)), 2)

    fun format(p: Primitives, key: ByteArray): String {
        require(key.size == 32) { "a Secret Key is 32 bytes" }
        return canonical(p, key).chunked(6).joinToString("-")
    }

    /** Case folded, spaces and hyphens dropped, I and L read as 1, O as 0. */
    fun fold(typed: String): String = typed.uppercase().filterNot { it.isWhitespace() || it == '-' }.replace('I', '1').replace('L', '1').replace('O', '0')

    /** The 32 bytes of a typed Secret Key, or null when it is malformed or carries a typo. */
    fun read(p: Primitives, typed: String): ByteArray? {
        val s = fold(typed)
        if (s.length != SYMBOLS || s.any { it !in CROCKFORD }) return null
        val bits = s.flatMap { c -> CROCKFORD.indexOf(c).let { v -> (4 downTo 0).map { (v shr it) and 1 } } }
        val key = ByteArray(32) { i -> (0 until 8).fold(0) { v, j -> (v shl 1) or bits[i * 8 + j] }.toByte() }
        if (canonical(p, key) != s) {
            key.fill(0)
            return null
        }
        return key
    }
}
