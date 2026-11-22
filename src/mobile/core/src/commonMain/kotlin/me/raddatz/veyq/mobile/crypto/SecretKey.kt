package me.raddatz.veyq.mobile.crypto

import kotlinx.serialization.Serializable

/**
 * Why a typed Secret Key is not one (the web's `SecretKeyProblem`, ADR 0154 decision 1: an outcome a UI tells apart is
 * typed): [MALFORMED] — not 54 Crockford symbols at all (too short, too long, a symbol outside the alphabet); [TYPO] — 54
 * symbols, but not the ones any key prints as (the check symbols disagree, or the last body symbol carries bits a key
 * never sets): a symbol was misread or mistyped. A Secret-Key field says the typo while it is typed; a malformed entry
 * says nothing until the action refuses it as a key that does not match. `v1.json#secret_key_text.refused` pins which
 * text is which, for this reader and `@veyq/crypto`'s alike.
 */
@Serializable
enum class SecretKeyProblem { MALFORMED, TYPO }

/** A typed Secret Key read ([SecretKeyText.reading]): its 32 bytes ([Key], the caller zeroes them) or why it is none. */
sealed interface SecretKeyReading {
    class Key(val key: ByteArray) : SecretKeyReading
    data class Unreadable(val problem: SecretKeyProblem) : SecretKeyReading
}

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

    /** A typed Secret Key read: its 32 bytes, or which of the two ways it is none ([SecretKeyProblem]). */
    fun reading(p: Primitives, typed: String): SecretKeyReading {
        val s = fold(typed)
        if (s.length != SYMBOLS || s.any { it !in CROCKFORD }) return SecretKeyReading.Unreadable(SecretKeyProblem.MALFORMED)
        val bits = s.flatMap { c -> CROCKFORD.indexOf(c).let { v -> (4 downTo 0).map { (v shr it) and 1 } } }
        val key = ByteArray(32) { i -> (0 until 8).fold(0) { v, j -> (v shl 1) or bits[i * 8 + j] }.toByte() }
        // Re-spelling the decoded bytes is the whole check: the check symbols AND the four bits of the last body symbol
        // no key sets. A typo's bytes are one symbol away from a key, so they do not outlive the reading.
        if (canonical(p, key) != s) {
            key.fill(0)
            return SecretKeyReading.Unreadable(SecretKeyProblem.TYPO)
        }
        return SecretKeyReading.Key(key)
    }

    /** The 32 bytes of a typed Secret Key, or null when it is none ([reading] says which way). */
    fun read(p: Primitives, typed: String): ByteArray? = (reading(p, typed) as? SecretKeyReading.Key)?.key

    /** Why [typed] is no Secret Key, or null where it reads as one (its bytes zeroed at once). */
    fun problem(p: Primitives, typed: String): SecretKeyProblem? = when (val r = reading(p, typed)) {
        is SecretKeyReading.Key -> null.also { r.key.fill(0) }
        is SecretKeyReading.Unreadable -> r.problem
    }
}
