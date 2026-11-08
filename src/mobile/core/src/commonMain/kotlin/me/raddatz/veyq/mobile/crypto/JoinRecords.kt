package me.raddatz.veyq.mobile.crypto

/**
 * A member's join record and what anchors it (ADR 0111 decisions 3, 12 and 21; ADR 0115), ported from
 * `src/packages/crypto/src/invite.ts` and the value keys of `v1.ts`: the owner signs an invite key, the invite key and
 * the newcomer's identity both sign the record, and an owner whose key moved since endorses the record with the new key.
 * The record is sealed under a value key of its own, wrapped under the tenant key. Pinned by `v1.json#invite_key`,
 * `#join_record`, `#join_endorsement` and `#value_key`. Every check reads bytes a server served: none throws.
 */
class JoinRecords(private val suite: V1) {
    private val p = suite.p

    /** A decoded join record: public keys are serialized bundles, [inviteExpiresAt] Unix seconds. */
    class JoinRecord(
        val identityPublicKey: ByteArray,
        val secretKeyProofPublicKey: ByteArray,
        val inviteId: String,
        val invitePublicKey: ByteArray,
        val inviteKeySignature: ByteArray,
        val inviteExpiresAt: Long,
        val signerUserId: String,
        val signerIdentityPublicKey: ByteArray,
        val inviteSignature: ByteArray,
        val identitySignature: ByteArray,
    )

    fun inviteKeyStatement(tenantId: String, inviteId: String, invitePublicKey: ByteArray, expiresAt: Long): ByteArray =
        encodeContext(
            Purposes.STATEMENT,
            Context("invite-key", "tenantId" to tenantId, "inviteId" to inviteId, "inviteKeyDigest" to p.sha256(invitePublicKey), "expiresAt" to expiresAt),
        )

    fun joinRecordStatement(tenantId: String, userId: String, r: JoinRecord): ByteArray =
        encodeContext(
            Purposes.STATEMENT,
            Context(
                "join-record",
                "tenantId" to tenantId, "userId" to userId, "identityDigest" to p.sha256(r.identityPublicKey),
                "secretKeyProofPublicKey" to r.secretKeyProofPublicKey, "inviteId" to r.inviteId,
                "inviteKeyDigest" to p.sha256(r.invitePublicKey), "inviteKeySignature" to r.inviteKeySignature,
                "inviteExpiresAt" to r.inviteExpiresAt, "signerUserId" to r.signerUserId,
                "signerIdentityDigest" to p.sha256(r.signerIdentityPublicKey),
            ),
        )

    /** The owner signed the invite key, and the invite key and the new identity both signed the record. */
    fun verifies(tenantId: String, userId: String, r: JoinRecord): Boolean = runCatching {
        val stmt = joinRecordStatement(tenantId, userId, r)
        val invite = suite.deserializeIdentityPublic(r.invitePublicKey)
        val identity = suite.deserializeIdentityPublic(r.identityPublicKey)
        val signer = suite.deserializeIdentityPublic(r.signerIdentityPublicKey)
        p.ed25519Verify(signer.sign, inviteKeyStatement(tenantId, r.inviteId, r.invitePublicKey, r.inviteExpiresAt), r.inviteKeySignature) &&
            p.ed25519Verify(invite.sign, stmt, r.inviteSignature) &&
            p.ed25519Verify(identity.sign, stmt, r.identitySignature)
    }.getOrDefault(false)

    /** `0x01 ‖ lp(identity) ‖ lp(proofKey) ‖ lp(inviteId) ‖ lp(inviteKey) ‖ lp(inviteKeySignature) ‖ lp(u64be(expiresAt))
     *  ‖ lp(signerUserId) ‖ lp(signerKey) ‖ lp(inviteSignature) ‖ lp(identitySignature)`. */
    fun encode(r: JoinRecord): ByteArray {
        require(r.inviteExpiresAt >= 0) { "join record: expiry" }
        return concat(
            byteArrayOf(VERSION), lp(r.identityPublicKey), lp(r.secretKeyProofPublicKey), lp(utf8(r.inviteId)), lp(r.invitePublicKey),
            lp(r.inviteKeySignature), lp(u64be(r.inviteExpiresAt)), lp(utf8(r.signerUserId)), lp(r.signerIdentityPublicKey),
            lp(r.inviteSignature), lp(r.identitySignature),
        )
    }

    /** The record decoded, or null for anything that is not one: a version, exactly ten fields, an 8-byte expiry. */
    fun decode(b: ByteArray): JoinRecord? = runCatching {
        if (b.isEmpty() || b[0] != VERSION) return null
        val f = splitLp(b.copyOfRange(1, b.size), 10)
        if (f[5].size != 8) return null
        val expiry = f[5].fold(0L) { acc, x -> (acc shl 8) or (x.toLong() and 0xff) }
        if (expiry < 0) return null
        JoinRecord(f[0], f[1], f[2].decodeToString(), f[3], f[4], expiry, f[6].decodeToString(), f[7], f[8], f[9])
    }.getOrNull()

    /** The record's own field-seal context, bound to (tenant, member). */
    fun recordContext(tenantId: String, userId: String) = Context("member-join-record", "scopeId" to tenantId, "userId" to userId)

    /**
     * Opens a member's record: its value key from [wrappedValueKey] with the tenant key of [epoch], the record under it.
     * Null for a wrap or a record that does not open: a wrap a rotator junked reads as no record, never as another's.
     */
    fun open(tenantId: String, userId: String, tenantKey: ByteArray, epoch: Long, wrappedValueKey: ByteArray, sealed: ByteArray): JoinRecord? =
        openValueField(tenantId, epoch, tenantKey, recordContext(tenantId, userId), wrappedValueKey, sealed)?.let(::decode)

    /**
     * A field sealed under a value key of its own (ADR 0115), as every reader opens it: the value key from its wrap under
     * the scope key of [epoch], bound to the field's own address, then the field under it. Null for a wrap or a field that
     * does not open — the wrong key, another member's wrap, a wrap a rotator junked: "nothing readable", never another's.
     */
    fun openValueField(scopeId: String, epoch: Long, scopeKey: ByteArray, ctx: Context, wrappedValueKey: ByteArray, sealed: ByteArray): ByteArray? = runCatching {
        val valueKey = suite.open(valueKeyContext(scopeId, epoch, valueAddress(Purposes.FIELD, ctx)), scopeKey, wrappedValueKey)
        try {
            suite.openField(ctx, valueKey, sealed)
        } finally {
            valueKey.fill(0)
        }
    }.getOrNull()

    /** A value's address: SHA-256 of its own context in its own schema (`encodeValueContext`). */
    fun valueAddress(schema: Schema, context: Context): ByteArray {
        if (context.purpose !in VALUE_PURPOSES) throw SealContextInvalid("${context.purpose} is not sealed under a value key")
        return p.sha256(encodeContext(schema, context))
    }

    fun valueKeyContext(scopeId: String, epoch: Long, valueAddress: ByteArray) =
        Context("value-key", "scopeId" to scopeId, "epoch" to epoch, "valueAddress" to valueAddress)

    fun endorsementStatement(tenantId: String, userId: String, signerUserId: String, joinRecord: ByteArray): ByteArray =
        encodeContext(
            Purposes.STATEMENT,
            Context("join-endorsement", "tenantId" to tenantId, "userId" to userId, "signerUserId" to signerUserId, "recordDigest" to p.sha256(joinRecord)),
        )

    /** Whether [signature] is the owner's ([ownerSignPublicKey]) over the OPENED record [joinRecord] at this address. */
    fun endorsementVerifies(ownerSignPublicKey: ByteArray, tenantId: String, userId: String, signerUserId: String, joinRecord: ByteArray, signature: ByteArray): Boolean =
        runCatching { p.ed25519Verify(ownerSignPublicKey, endorsementStatement(tenantId, userId, signerUserId, joinRecord), signature) }.getOrDefault(false)

    companion object {
        private const val VERSION: Byte = 0x01

        /** `seal.ts` `VALUE_KEY_PURPOSES`: the purposes sealed under a value key of their own. */
        val VALUE_PURPOSES = setOf(
            "group-name", "space-icon", "share-key-recovery", "item-favorites",
            "member-name", "member-avatar", "member-join-record", "member-verification",
        )
    }
}
