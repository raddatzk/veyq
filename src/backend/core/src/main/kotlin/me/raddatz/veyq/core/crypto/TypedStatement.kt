package me.raddatz.veyq.core.crypto

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64

/**
 * The byte helpers of `packages/crypto`'s typed statement encoding (`seal.ts`'s `encodeStatement`): a statement is
 * `lp(utf8(purpose)) || lp(field)…`, where `lp` prefixes a big-endian u32 length, an id is its canonical UTF-8 text, an
 * integer is u64be, and a digest or key is its raw bytes. Shared by the server's rebuilds of the `grant` statement
 * ([GrantSignature]) and the key chain's two ([KeyChainStatements]); [InviteAcceptProof] keeps its own copy, older than
 * this one. Auth crypto, not content crypto: public values in, bytes out.
 */
internal object TypedStatement {
    private val CANONICAL_UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    fun encode(purpose: String, vararg fields: ByteArray): ByteArray =
        (listOf(purpose.toByteArray(Charsets.UTF_8)) + fields).fold(ByteArray(0)) { acc, f -> acc + lp(f) }

    /** The canonical UTF-8 of an id, or null for anything that is not a canonical lowercase UUID (`seal.ts` refuses it). */
    fun id(value: String): ByteArray? = if (CANONICAL_UUID.matches(value)) value.toByteArray(Charsets.UTF_8) else null

    fun lp(b: ByteArray): ByteArray = ByteBuffer.allocate(4).putInt(b.size).array() + b

    fun u64(n: Long): ByteArray = ByteBuffer.allocate(8).putLong(n).array()

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    /** Standard base64, or null — a malformed input is a refusal, never an exception. */
    fun decode(b64: String?): ByteArray? = b64?.let {
        try {
            Base64.getDecoder().decode(it)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun encodeB64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

    /** The Ed25519 half of an identity bundle `lp(kem) || lp(sign)` (`serializeIdentityPublic`), or null for anything else. */
    fun signHalf(bundle: ByteArray): ByteArray? {
        val buf = ByteBuffer.wrap(bundle)
        if (buf.remaining() < 4) return null
        val kemLen = buf.int
        if (kemLen < 0 || buf.remaining() < kemLen.toLong() + 4) return null
        buf.position(buf.position() + kemLen)
        val signLen = buf.int
        if (signLen != 32 || buf.remaining() != 32) return null
        return ByteArray(32).also { buf.get(it) }
    }

    /** Whether [signature] verifies over [message] under the Ed25519 half of the identity [bundle]; false for any
     *  malformed input. */
    fun identityVerifies(bundle: ByteArray?, message: ByteArray?, signature: ByteArray?): Boolean {
        val key = bundle?.let(::signHalf) ?: return false
        if (message == null || signature == null) return false
        return Ed25519Verifier.verify(key, message, signature)
    }
}
