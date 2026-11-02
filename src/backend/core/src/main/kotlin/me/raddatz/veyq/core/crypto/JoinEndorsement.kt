package me.raddatz.veyq.core.crypto

/**
 * The server's rebuild of the `join-endorsement` statement (ADR 0111 decision 21; `seal.ts`
 * `STATEMENT_PURPOSES['join-endorsement']`, `invite.ts` `joinEndorsementStatement`): an inviting owner's identity
 * signature over a join record they rooted —
 * `lp(utf8("join-endorsement")) || lp(utf8(tenantId)) || lp(utf8(userId)) || lp(utf8(signerUserId)) ||
 * lp(recordDigest)`, `recordDigest` being SHA-256 of the **opened** record (ADR 0115 decision 8): a rotation re-seals
 * every join record under the new tenant key, and an endorsement bound to the envelope would die with each re-seal,
 * which only the owner could sign again. The server cannot open the record, so it checks the signature over the digest
 * the client names; the reader, which opens the record anyway, checks the digest against it.
 *
 * **Where the server checks one**: an endorsement an owner's securing stages (`PUT
 * /api/account/rotation/stage/{stageId}/join-endorsements`), verified under the identity the stage declares over the
 * staged `recordDigest` — a consistency check of the stage against the identity the publish then registers, like a
 * staged self-grant's ([GrantSignature]). Whether a record counts stays every reader's judgment (`loadTenantView`).
 *
 * Pinned for both stacks by the `join_endorsement` vector in `contracts/crypto/vectors/v1.json`; `JoinEndorsementTest`
 * asserts this side against it. Auth crypto, not content crypto: public values in, a yes or no out.
 */
object JoinEndorsement {

    private const val LABEL = "join-endorsement"

    /** The statement over [recordDigest] (base64, 32 bytes), or null when an id is no canonical UUID or the digest is
     *  no base64 SHA-256. */
    fun statement(tenantId: String, userId: String, signerUserId: String, recordDigest: String?): ByteArray? =
        TypedStatement.encode(
            LABEL,
            TypedStatement.id(tenantId) ?: return null,
            TypedStatement.id(userId) ?: return null,
            TypedStatement.id(signerUserId) ?: return null,
            TypedStatement.decode(recordDigest)?.takeIf { it.size == DIGEST_BYTES } ?: return null,
        )

    /** Whether [endorsement] (base64) is the signature of the identity bundle [signerIdentityPublicKey] (base64) over
     *  the record whose digest is [recordDigest] at this address. Every malformed input is false, never an exception. */
    fun verifies(
        signerIdentityPublicKey: String?, tenantId: String, userId: String, signerUserId: String,
        recordDigest: String?, endorsement: String?,
    ): Boolean = TypedStatement.identityVerifies(
        TypedStatement.decode(signerIdentityPublicKey),
        statement(tenantId, userId, signerUserId, recordDigest),
        TypedStatement.decode(endorsement),
    )

    private const val DIGEST_BYTES = 32
}
