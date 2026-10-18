package me.raddatz.veyq.core.crypto

import me.raddatz.veyq.core.identity.normalizeEmail
import java.nio.ByteBuffer
import java.util.Base64

/**
 * The attestation a change of the sign-in address carries (uc.account.change-email): an Ed25519 signature with the
 * account's **current** auth key — the half of the cold credential a blind server can check for itself, since it
 * holds `auth_public_key` (ADR 0003) — over the account, the new address and the auth key derived under it.
 *
 * A password change's attestation signs the new key's raw bytes alone. This one is domain-separated and binds the
 * address and the account, so neither attestation can stand for the other, and a captured one installs nothing
 * under another address or account. The server rebuilds the statement from the request and the caller; the byte
 * layout is `packages/crypto`'s (`emailChangeAttestationStatement`), pinned for both stacks by the
 * `auth_attestation` vector and asserted here by `AuthAttestationTest`. Auth crypto, not content crypto.
 */
object AuthAttestation {

    /** The statement, or null when [authPublicKey] is not base64 — a refusal, never an exception. */
    fun emailChangeStatement(userId: String, email: String, authPublicKey: String): ByteArray? = runCatching {
        listOf(
            "veyq/v1/auth-attestation/change-email".toByteArray(Charsets.UTF_8),
            userId.toByteArray(Charsets.UTF_8),
            normalizeEmail(email).toByteArray(Charsets.UTF_8),
            Base64.getDecoder().decode(authPublicKey),
        ).fold(ByteArray(0)) { acc, f -> acc + ByteBuffer.allocate(4).putInt(f.size).array() + f }
    }.getOrNull()

    /**
     * The password proof of an account's own erasure (uc.account.erase-account, ADR 0101 decision 1): the account and a
     * login challenge the server minted for its address, spent by the request, so a captured proof erases nothing twice.
     * Byte layout: `packages/crypto`'s `eraseAccountAttestationStatement`, pinned by the `auth_attestation.erase_account`
     * vector. Null when [nonce] is not base64.
     */
    fun eraseAccountStatement(userId: String, nonce: String): ByteArray? = runCatching {
        listOf(
            "veyq/v1/auth-attestation/erase-account".toByteArray(Charsets.UTF_8),
            userId.toByteArray(Charsets.UTF_8),
            Base64.getDecoder().decode(nonce),
        ).fold(ByteArray(0)) { acc, f -> acc + ByteBuffer.allocate(4).putInt(f.size).array() + f }
    }.getOrNull()

    /** True iff [signature] verifies over [statement] under the raw base64 [publicKey]; malformed input is false. */
    fun verify(publicKey: String?, statement: ByteArray?, signature: String): Boolean {
        if (publicKey == null || statement == null) return false
        val key = runCatching { Base64.getDecoder().decode(publicKey) }.getOrNull() ?: return false
        val sig = runCatching { Base64.getDecoder().decode(signature) }.getOrNull() ?: return false
        return Ed25519Verifier.verify(key, statement, sig)
    }
}
