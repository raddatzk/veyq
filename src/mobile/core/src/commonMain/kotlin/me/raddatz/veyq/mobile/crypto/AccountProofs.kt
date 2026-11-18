package me.raddatz.veyq.mobile.crypto

/**
 * What the account's flows sign (ADR 0148 decision 4), ported from `src/packages/crypto/src/v1.ts`: the Secret-Key
 * proof key and the four statements it signs (ADR 0060 decision 2: rotation, change-password, recovery, change-email),
 * the auth key's attestations (change-email, erase-account; a password change's signs the new key's raw bytes), and the
 * digest a rotation binds its staged self-grants by (ADR 0111 decision 6). Pinned by `v1.json#secret_key_proof`,
 * `#auth_attestation` and `#rotation_stage`.
 *
 * Each statement is `lp(utf8(domain)) ‖ lp(field)…`, every field the DECODED bytes of its wire value. Nothing here keeps
 * a key: a caller zeroes what it passed in.
 */
class AccountProofs(private val p: Primitives) {

    /** One wrap as a statement sees it: its ciphertext and its KDF params, both decoded. */
    class ProofWrap(val wrappedKey: ByteArray, val kdfParams: ByteArray)

    /** The proof key's seed (`HKDF-SHA256(secretKey, "", "veyq/v1/secret-key-proof")`) — its Ed25519 private half. */
    fun secretKeyProofSeed(secretKey: ByteArray): ByteArray = p.hkdfSha256(secretKey, ByteArray(0), utf8("veyq/v1/secret-key-proof"), 32)

    /** The proof key's public half, as the server stores it and a key-chain step installs it. */
    fun secretKeyProofPublic(secretKey: ByteArray): ByteArray {
        val seed = secretKeyProofSeed(secretKey)
        try {
            return p.ed25519Public(seed)
        } finally {
            seed.fill(0)
        }
    }

    /** Sign [statement] with the proof key of [secretKey]; the derived seed is zeroed before this returns. */
    fun secretKeyProofSign(secretKey: ByteArray, statement: ByteArray): ByteArray {
        val seed = secretKeyProofSeed(secretKey)
        try {
            return p.ed25519Sign(seed, statement)
        } finally {
            seed.fill(0)
        }
    }

    private fun wrapDigest(w: ProofWrap): ByteArray = p.sha256(concat(lp(w.wrappedKey), lp(w.kdfParams)))

    private fun statement(domain: String, vararg fields: ByteArray): ByteArray =
        concat(lp(utf8(domain)), *fields.map(::lp).toTypedArray())

    /** Securing the account (uc.account.rotate-keys): the identity it retires and the one it installs, both wraps, the
     *  key-chain step, both auth keys, the new proof key and the staged self-grants (their stage and digest). */
    @Suppress("LongParameterList")
    fun rotationProofStatement(
        currentIdentityPublicKey: ByteArray,
        identityPublicKey: ByteArray,
        wrappedIdentityPrivateKey: ByteArray,
        passwordWrap: ProofWrap,
        recoveryWrap: ProofWrap,
        keyChainStepStatement: ByteArray,
        currentAuthPublicKey: ByteArray,
        authPublicKey: ByteArray,
        secretKeyProofPublicKey: ByteArray,
        stageId: String,
        stagedGrantsDigest: ByteArray,
    ): ByteArray {
        if (!CANONICAL_UUID.matches(stageId)) throw SealContextInvalid("rotation stageId is not a canonical lowercase UUID")
        if (stagedGrantsDigest.size != 32) throw SealContextInvalid("rotation stagedGrantsDigest is not a SHA-256 digest")
        return statement(
            "veyq/v1/secret-key-proof/rotation",
            p.sha256(currentIdentityPublicKey),
            p.sha256(identityPublicKey),
            p.sha256(wrappedIdentityPrivateKey),
            wrapDigest(passwordWrap),
            wrapDigest(recoveryWrap),
            p.sha256(keyChainStepStatement),
            currentAuthPublicKey,
            authPublicKey,
            secretKeyProofPublicKey,
            utf8(stageId),
            stagedGrantsDigest,
        )
    }

    /** Change-password: the auth key it replaces, the one it installs, and the new `PASSWORD` floor. */
    fun passwordChangeProofStatement(currentAuthPublicKey: ByteArray, authPublicKey: ByteArray, passwordWrap: ProofWrap): ByteArray =
        statement("veyq/v1/secret-key-proof/change-password", currentAuthPublicKey, authPublicKey, wrapDigest(passwordWrap))

    /** Recovery completion: the one-time token it spends (its UTF-8 bytes, hashed), the new auth key, the new floor. */
    fun recoveryProofStatement(token: String, authPublicKey: ByteArray, passwordWrap: ProofWrap): ByteArray =
        statement("veyq/v1/secret-key-proof/recovery", p.sha256(utf8(token)), authPublicKey, wrapDigest(passwordWrap))

    /** Change-email: the auth key it replaces, the one derived under the new address's salt, and that address. */
    fun emailChangeProofStatement(currentAuthPublicKey: ByteArray, authPublicKey: ByteArray, email: String): ByteArray =
        statement("veyq/v1/secret-key-proof/change-email", currentAuthPublicKey, authPublicKey, normalizedEmail(email))

    /** Change-email's attestation, signed by the CURRENT auth key: the account, the new address, the new auth key. */
    fun emailChangeAttestationStatement(userId: String, email: String, authPublicKey: ByteArray): ByteArray =
        statement("veyq/v1/auth-attestation/change-email", utf8(userId), normalizedEmail(email), authPublicKey)

    /** Erase-account's password proof: the account and the DECODED nonce of a login challenge the request spends. */
    fun eraseAccountAttestationStatement(userId: String, nonce: ByteArray): ByteArray =
        statement("veyq/v1/auth-attestation/erase-account", utf8(userId), nonce)

    /** A password change's attestation: the CURRENT auth key over the new auth public key's raw bytes, nothing else. */
    fun passwordChangeAttestation(currentAuthSeed: ByteArray, newAuthPublicKey: ByteArray): ByteArray =
        p.ed25519Sign(currentAuthSeed, newAuthPublicKey)

    /** One staged self-grant as the digest reads it: the scope, the epoch whose key it carries, its DECODED ciphertext. */
    class StageEntry(val scopeId: String, val epoch: Long, val wrappedGrant: ByteArray)

    /** `SHA-256(utf8("veyq/v1/rotation-stage") ‖ lp(utf8(scopeId)) ‖ lp(u64be(epoch)) ‖ lp(SHA-256(wrappedGrant)) …)` over
     *  the entries in ascending `scopeId` order (canonical lowercase UUIDs, so text order is byte order), one per scope. */
    fun rotationStageDigest(entries: List<StageEntry>): ByteArray {
        for (e in entries) {
            if (!CANONICAL_UUID.matches(e.scopeId)) throw SealContextInvalid("rotation-stage scopeId is not a canonical lowercase UUID")
            if (e.epoch < 0) throw SealContextInvalid("rotation-stage epoch is not an unsigned integer")
        }
        val byScope = entries.associateBy { it.scopeId }
        if (byScope.size != entries.size) throw SealContextInvalid("rotation stage holds one scope twice")
        val parts = mutableListOf(utf8("veyq/v1/rotation-stage"))
        for (scopeId in byScope.keys.sorted()) {
            val e = byScope.getValue(scopeId)
            parts += lp(utf8(scopeId))
            parts += lp(u64be(e.epoch))
            parts += lp(p.sha256(e.wrappedGrant))
        }
        return p.sha256(concat(*parts.toTypedArray()))
    }

    private companion object {
        val CANONICAL_UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

        /** The one email normalization (trim, lower-case): `deriveAuthSalt`'s rule and the server's. */
        fun normalizedEmail(email: String): ByteArray = utf8(email.trim().lowercase())
    }
}
