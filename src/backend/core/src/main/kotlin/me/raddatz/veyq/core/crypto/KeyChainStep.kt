package me.raddatz.veyq.core.crypto

/**
 * The server half of an account's key chain (ADR 0111 decision 7, cr-secure-the-account).
 *
 * Step 0 is the typed statement `key-chain-genesis` — `lp(utf8("key-chain-genesis")) || lp(utf8(userId)) ||
 * lp(identity bundle) || lp(Secret-Key proof public key)` — the keys the account was born with, unsigned. Each securing
 * of the account appends a step `i ≥ 1`, the typed statement `key-chain-step` — `lp(utf8("key-chain-step")) ||
 * lp(utf8(userId)) || lp(u64be(i)) || lp(previousHead) || lp(sha256(retired bundle)) || lp(sha256(retired proof key)) ||
 * lp(new bundle) || lp(new proof key)` — signed over those bytes by the **retired** identity AND the **retired** proof
 * key: a thief holding a signed-in device's identity never holds the Secret Key the second signature needs. The head of a
 * step is `sha256(utf8("veyq/v1/key-chain/head") || statement)`; one head per account, the same in every tenant.
 *
 * The server **rebuilds** the step from the stored chain and the keys the request installs, and checks both signatures
 * under the stored current keys — an honest server's check; every reader walks the chain itself (`verifyKeyChain`).
 * Every key field is the DECODED bytes of its base64 wire value. Pinned for both stacks by the `key_chain` vector;
 * `KeyChainStepTest` asserts this side against it, every failure case included. Auth crypto, not content crypto.
 */
object KeyChainStatements {

    private val HEAD_DOMAIN = "veyq/v1/key-chain/head".toByteArray(Charsets.UTF_8)

    /** Step 0, or null when [userId] is no canonical UUID or a key is no base64. */
    fun genesis(userId: String, identityPublicKey: String, secretKeyProofPublicKey: String): ByteArray? =
        TypedStatement.encode(
            "key-chain-genesis",
            TypedStatement.id(userId) ?: return null,
            TypedStatement.decode(identityPublicKey) ?: return null,
            TypedStatement.decode(secretKeyProofPublicKey) ?: return null,
        )

    /**
     * Step [index] (≥ 1) after the head [previousHead], retiring [retiredIdentityPublicKey] and
     * [retiredSecretKeyProofPublicKey] for [identityPublicKey] and [secretKeyProofPublicKey] — or null for any malformed
     * input (an index below 1, a head that is no SHA-256 digest, a non-canonical id, a key that is no base64).
     */
    fun step(
        userId: String, index: Long, previousHead: ByteArray,
        retiredIdentityPublicKey: String, retiredSecretKeyProofPublicKey: String,
        identityPublicKey: String, secretKeyProofPublicKey: String,
    ): ByteArray? {
        if (index < 1 || previousHead.size != 32) return null
        return TypedStatement.encode(
            "key-chain-step",
            TypedStatement.id(userId) ?: return null,
            TypedStatement.u64(index),
            previousHead,
            TypedStatement.sha256(TypedStatement.decode(retiredIdentityPublicKey) ?: return null),
            TypedStatement.sha256(TypedStatement.decode(retiredSecretKeyProofPublicKey) ?: return null),
            TypedStatement.decode(identityPublicKey) ?: return null,
            TypedStatement.decode(secretKeyProofPublicKey) ?: return null,
        )
    }

    /** The head a statement makes when it is the newest. */
    fun head(statement: ByteArray): ByteArray = TypedStatement.sha256(HEAD_DOMAIN + statement)

    /** Whether [identitySignature] (base64) verifies over [statement] under the Ed25519 half of the retired identity
     *  bundle [retiredIdentityPublicKey]. False for any malformed input. */
    fun identityHalfVerifies(retiredIdentityPublicKey: String?, statement: ByteArray?, identitySignature: String?): Boolean =
        TypedStatement.identityVerifies(TypedStatement.decode(retiredIdentityPublicKey), statement, TypedStatement.decode(identitySignature))

    /** Whether [proofSignature] (base64) verifies over [statement] under the retired raw Ed25519 proof key
     *  [retiredSecretKeyProofPublicKey]. False for any malformed input, a missing key included. */
    fun proofHalfVerifies(retiredSecretKeyProofPublicKey: String?, statement: ByteArray?, proofSignature: String?): Boolean {
        val key = TypedStatement.decode(retiredSecretKeyProofPublicKey) ?: return false
        val sig = TypedStatement.decode(proofSignature) ?: return false
        if (statement == null) return false
        return Ed25519Verifier.verify(key, statement, sig)
    }
}
