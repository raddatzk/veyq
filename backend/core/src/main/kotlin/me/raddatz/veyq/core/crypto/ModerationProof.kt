package me.raddatz.veyq.core.crypto

import java.nio.ByteBuffer

/**
 * The server half of the moderation key proof (ADR 0099 § 2): every key-proved moderation call carries an Ed25519
 * signature with the **current** epoch's moderation signing key over the statement `moderation-proof` — the server's
 * single-use challenge, the contract operation, and the case it acts on (or none). The server rebuilds the statement
 * here from the challenge it issued, the operation it matched and the case id in the path, so a client cannot choose
 * what it signed, and verifies it against the epoch's stored public key. A session alone is worth nothing without it.
 *
 * **Auth crypto, not content crypto** — the class of [SecretKeyProof]: a signature check over public values. The
 * moderation key's private half never reaches the server.
 *
 * The byte layout is `packages/crypto`'s typed statement encoding (`seal.ts` `STATEMENT_PURPOSES['moderation-proof']`):
 * `lp(utf8("moderation-proof")) || lp(challenge) || lp(utf8(operationId)) || lp(utf8(caseId) or empty)`, pinned for
 * both stacks by the `moderation_proof` vector in `contracts/crypto/vectors/v1.json`; `ModerationProofTest` asserts
 * this side against it.
 */
object ModerationProof {

    private const val LABEL = "moderation-proof"
    private val OPERATION_ID = Regex("^[a-z][A-Za-z0-9]{0,63}$")
    private val CANONICAL_UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    /**
     * The statement for [operationId] on [caseId] (null: an operation on no case) under [challenge], or null when an
     * input could not be part of one — an operation id that is no contract operationId, a case id that is not a
     * canonical UUID. A null statement verifies nothing.
     */
    fun statement(challenge: ByteArray, operationId: String, caseId: String?): ByteArray? {
        if (!OPERATION_ID.matches(operationId)) return null
        if (caseId != null && !CANONICAL_UUID.matches(caseId)) return null
        return listOf(
            LABEL.toByteArray(Charsets.UTF_8),
            challenge,
            operationId.toByteArray(Charsets.UTF_8),
            caseId?.toByteArray(Charsets.UTF_8) ?: ByteArray(0),
        ).fold(ByteArray(0)) { acc, f -> acc + lp(f) }
    }

    /** True iff [signature] verifies over [statement] under the raw 32-byte [signPublicKey]; any missing input is false. */
    fun verify(signPublicKey: ByteArray?, statement: ByteArray?, signature: ByteArray?): Boolean {
        if (signPublicKey == null || statement == null || signature == null) return false
        return Ed25519Verifier.verify(signPublicKey, statement, signature)
    }

    private fun lp(b: ByteArray): ByteArray = ByteBuffer.allocate(4).putInt(b.size).array() + b
}
