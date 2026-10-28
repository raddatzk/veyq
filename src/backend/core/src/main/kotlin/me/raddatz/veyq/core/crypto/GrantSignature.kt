package me.raddatz.veyq.core.crypto

/**
 * The server's rebuild of the `grant` statement (ADR 0028 Decision 6; `seal.ts` `STATEMENT_PURPOSES.grant`):
 * `lp(utf8("grant")) || lp(utf8(scopeId)) || lp(utf8(scopeKind)) || lp(u64be(epoch)) || lp(utf8(recipientUserId)) ||
 * lp(sha256(recipient bundle)) || lp(sha256(wrappedGrant))`, both byte strings DECODED from their wire base64.
 *
 * **The server checks a grant signature in exactly one place**: a staged self-grant of an account's rotation
 * (`putRotationStageGrants`, cr-secure-the-account decision 3), verified under the identity its stage declares. That is
 * a consistency check of the stage against the identity the publish then registers under the Secret Key's proof — not
 * a trust judgment about a key, which stays every recipient's (ADR 0028 Decision 6). Every other grant the server stores
 * and relays unverified.
 *
 * Pinned for both stacks by the `grant_signature` vector in `contracts/crypto/vectors/v1.json`; `GrantSignatureTest`
 * asserts this side against both its cases. Auth crypto, not content crypto.
 */
object GrantSignature {

    /** The statement, or null when an id is no canonical UUID, the epoch is negative or a byte string is no base64. */
    fun statement(
        scopeId: String, scopeKind: String, epoch: Long, recipientUserId: String,
        recipientIdentityPublicKey: String, wrappedGrant: String,
    ): ByteArray? {
        if (epoch < 0) return null
        return TypedStatement.encode(
            "grant",
            TypedStatement.id(scopeId) ?: return null,
            scopeKind.toByteArray(Charsets.UTF_8),
            TypedStatement.u64(epoch),
            TypedStatement.id(recipientUserId) ?: return null,
            TypedStatement.sha256(TypedStatement.decode(recipientIdentityPublicKey) ?: return null),
            TypedStatement.sha256(TypedStatement.decode(wrappedGrant) ?: return null),
        )
    }

    /** Whether [signature] (base64) verifies over [statement] under the Ed25519 half of the identity bundle
     *  [signerIdentityPublicKey] (base64). Every malformed input is false. */
    fun verify(signerIdentityPublicKey: String?, statement: ByteArray?, signature: String?): Boolean =
        TypedStatement.identityVerifies(TypedStatement.decode(signerIdentityPublicKey), statement, TypedStatement.decode(signature))
}
