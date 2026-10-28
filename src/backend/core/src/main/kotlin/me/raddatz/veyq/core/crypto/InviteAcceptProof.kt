package me.raddatz.veyq.core.crypto

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64

/**
 * The server half of a member invite's accept proof (ADR 0111 decision 3): the newcomer signs, with the invite key's
 * Ed25519 half, the statement `invite-accept` over the invite, its new account and SHA-256 of its identity bundle, and
 * the server verifies it under the invite key it stores before it writes the membership. The operator could forge one
 * (it can mint invites of its own); an honest server refuses a redeem by the token alone.
 *
 * **Auth crypto, not content crypto** — the class of [ModerationProof]: a signature check over public values. The
 * invite key's private half never reaches the server.
 *
 * The byte layout is `packages/crypto`'s typed statement encoding (`seal.ts` `STATEMENT_PURPOSES['invite-accept']`):
 * `lp(utf8("invite-accept")) || lp(utf8(inviteId)) || lp(utf8(userId)) || lp(sha256(identity bundle))`, pinned for
 * both stacks by the `join_record` vector in `contracts/crypto/vectors/v1.json`; `InviteAcceptProofTest` asserts this
 * side against it. An identity bundle is `lp(kem) || lp(sign)` (`serializeIdentityPublic`), its Ed25519 half 32 bytes.
 */
object InviteAcceptProof {

    private const val LABEL = "invite-accept"
    private val CANONICAL_UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    /** The statement, or null when an id is not a canonical UUID or the bundle is no base64. */
    fun statement(inviteId: String, userId: String, identityPublicKeyB64: String): ByteArray? {
        if (!CANONICAL_UUID.matches(inviteId) || !CANONICAL_UUID.matches(userId)) return null
        val identity = decode(identityPublicKeyB64) ?: return null
        return listOf(
            LABEL.toByteArray(Charsets.UTF_8),
            inviteId.toByteArray(Charsets.UTF_8),
            userId.toByteArray(Charsets.UTF_8),
            sha256(identity),
        ).fold(ByteArray(0)) { acc, f -> acc + lp(f) }
    }

    /** True iff [proofB64] verifies over the statement under the Ed25519 half of the invite bundle [invitePublicKeyB64].
     *  Any malformed input is false, never an exception. */
    fun verify(invitePublicKeyB64: String?, inviteId: String, userId: String, identityPublicKeyB64: String, proofB64: String?): Boolean {
        val sign = invitePublicKeyB64?.let(::decode)?.let(::signHalf) ?: return false
        val stmt = statement(inviteId, userId, identityPublicKeyB64) ?: return false
        val sig = proofB64?.let(::decode) ?: return false
        return Ed25519Verifier.verify(sign, stmt, sig)
    }

    /**
     * The owner's `invite-key` statement (`seal.ts` `STATEMENT_PURPOSES['invite-key']`, the `invite_key` vector):
     * `lp(utf8("invite-key")) || lp(utf8(tenantId)) || lp(utf8(inviteId)) || lp(sha256(invite bundle)) ||
     * lp(u64be(expiresAt in Unix seconds))`, or null for an id that is no canonical UUID, a bundle that is no base64 or
     * a negative expiry.
     */
    fun inviteKeyStatement(tenantId: String, inviteId: String, invitePublicKeyB64: String, expiresAtSeconds: Long): ByteArray? {
        if (!CANONICAL_UUID.matches(tenantId) || !CANONICAL_UUID.matches(inviteId) || expiresAtSeconds < 0) return null
        val bundle = decode(invitePublicKeyB64) ?: return null
        return listOf(
            "invite-key".toByteArray(Charsets.UTF_8),
            tenantId.toByteArray(Charsets.UTF_8),
            inviteId.toByteArray(Charsets.UTF_8),
            sha256(bundle),
            ByteBuffer.allocate(8).putLong(expiresAtSeconds).array(),
        ).fold(ByteArray(0)) { acc, f -> acc + lp(f) }
    }

    /**
     * Whether [signatureB64] is the inviting owner's signature over the invite key, under the Ed25519 half of
     * [signerIdentityPublicKeyB64] — the key the server holds for the caller. An honest server's check (the invitee's
     * client checks it against the fingerprint in the link either way): it keeps a session without the owner's unlocked
     * vault — a phished password — from minting an invite any client would carry or deliver to, the role the admission
     * tag played. False for any malformed input.
     */
    fun inviteKeyVerifies(
        signerIdentityPublicKeyB64: String?, tenantId: String, inviteId: String, invitePublicKeyB64: String,
        expiresAtSeconds: Long, signatureB64: String?,
    ): Boolean {
        val sign = signerIdentityPublicKeyB64?.let(::decode)?.let(::signHalf) ?: return false
        val stmt = inviteKeyStatement(tenantId, inviteId, invitePublicKeyB64, expiresAtSeconds) ?: return false
        val sig = signatureB64?.let(::decode) ?: return false
        return Ed25519Verifier.verify(sign, stmt, sig)
    }

    /** Whether [digestB64] is SHA-256 of the DECODED bundle [identityPublicKeyB64] — the check a grant's
     *  `recipientIdentityDigest` meets against the key the server stores for its recipient. */
    fun digestMatches(identityPublicKeyB64: String?, digestB64: String?): Boolean {
        val bundle = identityPublicKeyB64?.let(::decode) ?: return false
        val digest = digestB64?.let(::decode) ?: return false
        return MessageDigest.isEqual(sha256(bundle), digest)
    }

    /** The Ed25519 half of `lp(kem) || lp(sign)`, or null for anything else. */
    private fun signHalf(bundle: ByteArray): ByteArray? {
        val buf = ByteBuffer.wrap(bundle)
        if (buf.remaining() < 4) return null
        val kemLen = buf.int
        if (kemLen < 0 || buf.remaining() < kemLen + 4) return null
        buf.position(buf.position() + kemLen)
        val signLen = buf.int
        if (signLen != 32 || buf.remaining() != 32) return null
        return ByteArray(32).also { buf.get(it) }
    }

    private fun decode(b64: String): ByteArray? = try {
        Base64.getDecoder().decode(b64)
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    private fun lp(b: ByteArray): ByteArray = ByteBuffer.allocate(4).putInt(b.size).array() + b
}
