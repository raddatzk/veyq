package me.raddatz.veyq.core.crypto

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64

/**
 * The server half of the cold-credential gate (ADR 0060 Decision 2, ADR 0054 Decision 1).
 *
 * Every flow that mints a cold credential — rotation, change-password, recovery completion — carries an Ed25519
 * signature under the account's **Secret-Key proof key**: a keypair the client derives from the Secret Key alone,
 * whose public half is registered at account birth (`veyq_user.secret_key_proof_public_key`). A session is not
 * enough, because a device-link holder is handed one without a password and a phished password buys one; neither
 * has the Secret Key. The server rebuilds each statement here from the request and the account's current state,
 * so a client cannot choose what it signed, and verifies it against the stored public key.
 *
 * **This is auth crypto, not content crypto** — the same class as [Ed25519Verifier]: a signature check over
 * public values. Nothing here reads or produces a secret.
 *
 * The byte layout is `packages/crypto`'s (`v1.ts`) and is pinned for both stacks by the `secret_key_proof` vector
 * in `contracts/crypto/vectors/v1.json` (ADR 0017 rule 4); `SecretKeyProofTest` asserts this side against it.
 * Every field is the **decoded** bytes of its base64 wire value, so a non-canonical encoding of the same bytes
 * cannot make the two stacks disagree.
 */
object SecretKeyProof {

    /** The one wrap shape a statement covers: ciphertext + its KDF params, both base64 as on the wire. */
    data class Wrap(val wrappedKey: String, val kdfParams: String)

    /**
     * Rotation: the identity it replaces, and everything it installs. Binding the **current** identity is what
     * makes a captured signature useless after the rotation it authorised.
     */
    fun rotationStatement(
        currentIdentityPublicKey: String,
        identityPublicKey: String,
        wrappedIdentityPrivateKey: String,
        passwordWrap: Wrap,
        recoveryWrap: Wrap,
    ): ByteArray? = runCatching {
        statement(
            "veyq/v1/secret-key-proof/rotation",
            sha256(decode(currentIdentityPublicKey)),
            sha256(decode(identityPublicKey)),
            sha256(decode(wrappedIdentityPrivateKey)),
            wrapDigest(passwordWrap),
            wrapDigest(recoveryWrap),
        )
    }.getOrNull()

    /** Change-password: the auth key it replaces, the one it installs, and the new `PASSWORD` floor. */
    fun passwordChangeStatement(currentAuthPublicKey: String, authPublicKey: String, passwordWrap: Wrap): ByteArray? =
        runCatching {
            statement(
                "veyq/v1/secret-key-proof/change-password",
                decode(currentAuthPublicKey),
                decode(authPublicKey),
                wrapDigest(passwordWrap),
            )
        }.getOrNull()

    /**
     * Recovery completion: the token it spends (single-use and bound to one account, so it is the replaced state),
     * the new auth key and the new `PASSWORD` floor. The token is hashed as its UTF-8 text, never base64-decoded:
     * it is an opaque handle, and its text is what both stacks hold.
     */
    fun recoveryStatement(token: String, authPublicKey: String, passwordWrap: Wrap): ByteArray? = runCatching {
        statement(
            "veyq/v1/secret-key-proof/recovery",
            sha256(token.toByteArray(Charsets.UTF_8)),
            decode(authPublicKey),
            wrapDigest(passwordWrap),
        )
    }.getOrNull()

    /**
     * True iff [signature] verifies over [statement] under [publicKey]. Every missing or malformed input — no key
     * registered (an account born before the proof key existed), a statement that could not be built from
     * non-base64 input, a signature that is not base64 — is **false**, never an exception: a caller that cannot
     * prove the Secret Key is refused, whatever the reason it cannot.
     */
    fun verify(publicKey: String?, statement: ByteArray?, signature: String): Boolean {
        if (publicKey == null || statement == null) return false
        val key = runCatching { decode(publicKey) }.getOrNull() ?: return false
        val sig = runCatching { decode(signature) }.getOrNull() ?: return false
        return Ed25519Verifier.verify(key, statement, sig)
    }

    private fun statement(domain: String, vararg fields: ByteArray): ByteArray =
        (listOf(domain.toByteArray(Charsets.UTF_8)) + fields).fold(ByteArray(0)) { acc, f -> acc + lp(f) }

    private fun wrapDigest(w: Wrap): ByteArray = sha256(lp(decode(w.wrappedKey)) + lp(decode(w.kdfParams)))

    private fun lp(b: ByteArray): ByteArray = ByteBuffer.allocate(4).putInt(b.size).array() + b

    private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    private fun decode(b64: String): ByteArray = Base64.getDecoder().decode(b64)
}
