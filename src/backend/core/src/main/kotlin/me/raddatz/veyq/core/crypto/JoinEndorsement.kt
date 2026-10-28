package me.raddatz.veyq.core.crypto

/**
 * The server's rebuild of the `join-endorsement` statement (ADR 0111 decision 21; `seal.ts`
 * `STATEMENT_PURPOSES['join-endorsement']`, `invite.ts` `joinEndorsementStatement`): an inviting owner's identity
 * signature over a join record they rooted —
 * `lp(utf8("join-endorsement")) || lp(utf8(tenantId)) || lp(utf8(userId)) || lp(utf8(signerUserId)) ||
 * lp(sha256(sealed join record))`, the record being the DECODED bytes the server stores and serves as
 * `sealedJoinRecord`. Bound to the sealed bytes, so the server checks it without opening anything.
 *
 * **Where the server checks one**: an endorsement an owner's securing stages (`PUT
 * /api/account/rotation/stage/{stageId}/join-endorsements`), verified under the identity the stage declares over the
 * record as stored — a consistency check of the stage against the identity the publish then registers, like a staged
 * self-grant's ([GrantSignature]). Whether a record counts stays every reader's judgment (`loadTenantView`).
 *
 * Pinned for both stacks by the `join_endorsement` vector in `contracts/crypto/vectors/v1.json`; `JoinEndorsementTest`
 * asserts this side against it. Auth crypto, not content crypto: public values in, a yes or no out.
 */
object JoinEndorsement {

    private const val LABEL = "join-endorsement"

    /** The statement, or null when an id is no canonical UUID or the sealed record is no base64. */
    fun statement(tenantId: String, userId: String, signerUserId: String, sealedJoinRecord: String?): ByteArray? =
        TypedStatement.encode(
            LABEL,
            TypedStatement.id(tenantId) ?: return null,
            TypedStatement.id(userId) ?: return null,
            TypedStatement.id(signerUserId) ?: return null,
            TypedStatement.sha256(TypedStatement.decode(sealedJoinRecord) ?: return null),
        )

    /** Whether [endorsement] (base64) is the signature of the identity bundle [signerIdentityPublicKey] (base64) over
     *  this sealed record at this address. Every malformed input is false, never an exception. */
    fun verifies(
        signerIdentityPublicKey: String?, tenantId: String, userId: String, signerUserId: String,
        sealedJoinRecord: String?, endorsement: String?,
    ): Boolean = TypedStatement.identityVerifies(
        TypedStatement.decode(signerIdentityPublicKey),
        statement(tenantId, userId, signerUserId, sealedJoinRecord),
        TypedStatement.decode(endorsement),
    )
}
