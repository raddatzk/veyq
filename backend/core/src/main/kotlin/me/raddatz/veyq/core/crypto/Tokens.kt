package me.raddatz.veyq.core.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Opaque session tokens. A token is high-entropy random material handed to the client; the server keeps
 * only its SHA-256 hash, so a database dump yields no usable session (ADR 0004).
 */
object Tokens {
    private val rng = SecureRandom()
    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()

    /** A fresh opaque token (32 random bytes, base64url). */
    fun random(): String {
        val bytes = ByteArray(32)
        rng.nextBytes(bytes)
        return urlEncoder.encodeToString(bytes)
    }

    /** The hex SHA-256 of a token — what the server stores and looks up by. */
    fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
