package me.raddatz.veyq.core.account.rotation

import me.raddatz.veyq.core.identity.MasterKeyWrapEntity
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.identity.WrapMethod
import me.raddatz.veyq.core.membership.MembershipEntity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.util.UUID

/** One cold wrap as it arrives from the client: opaque ciphertext + its fresh KDF params. */
data class RewrittenWrap(val wrappedKey: String, val kdfParams: String)

/**
 * Persistence for securing the account (ADR 0060, ADR 0111 decisions 6-8, uc.account.rotate-keys).
 *
 * `RecoveryStore`'s division, for `RecoveryStore`'s reason: the account rewrite is **one method** rather than a
 * handler orchestrating five store calls, because its atomicity *is* the requirement. A rotation that publishes
 * the new identity but fails to rewrite a wrap leaves an account whose directory key nobody can wrap to and
 * whose Master Key nothing can open — a worse state than not having rotated at all.
 */
@ApplicationScoped
class RotationStore {

    /**
     * Replace the account's key material. **All of it or none** (ADR 0060 § Consequences).
     *
     * Each write is load-bearing:
     *  - the published `identityPublicKey` — from here every contact reaches it through the key chain from the head they
     *    pinned (ADR 0111 decision 15);
     *  - `wrappedIdentityPrivateKey` — the new identity private key under the **new** Master Key;
     *  - the `PASSWORD` and `RECOVERY` wraps, rewritten to hold the new Master Key under the **new** Secret Key (a fresh
     *    salt each), so the old Emergency Kit opens nothing from here on;
     *  - `authPublicKey` (the password the account keeps — the same key when it kept its password) and
     *    `secretKeyProofPublicKey` (the new Secret Key's), which every later credential flow verifies against;
     *  - the deletion of every **other** wrap.
     *
     * That deletion keeps [WrapMethod.SURVIVORS] and nothing else — a whitelist, shared with password change and
     * reset, and why it must be one is on [WrapMethod].
     *
     * Returns false, nothing written, when the account has no `PASSWORD` floor or no `RECOVERY` wrap to rewrite. Neither
     * can happen through any HTTP surface (`deleteWrap` whitelists the device methods, and every account is born with
     * both), but a rotation that silently *created* a floor would be worse than one that refuses.
     */
    @Transactional
    fun applyRotation(
        userId: String,
        identityPublicKey: String,
        wrappedIdentityPrivateKey: String,
        passwordWrap: RewrittenWrap,
        recoveryWrap: RewrittenWrap,
        authPublicKey: String,
        secretKeyProofPublicKey: String,
    ): Boolean {
        val user = VeyqUser.findById(userId) ?: return false
        val floor = wrapOf(userId, WrapMethod.PASSWORD) ?: return false
        val recovery = wrapOf(userId, WrapMethod.RECOVERY) ?: return false

        floor.wrappedKey = passwordWrap.wrappedKey
        floor.kdfParams = passwordWrap.kdfParams
        recovery.wrappedKey = recoveryWrap.wrappedKey
        recovery.kdfParams = recoveryWrap.kdfParams

        user.identityPublicKey = identityPublicKey
        user.wrappedIdentityPrivateKey = wrappedIdentityPrivateKey
        user.authPublicKey = authPublicKey
        user.secretKeyProofPublicKey = secretKeyProofPublicKey

        MasterKeyWrapEntity.delete("userId = ?1 and method not in ?2", userId, WrapMethod.SURVIVORS)
        return true
    }

    /**
     * Write one staged self-grant as [userId]'s grant on [scopeId] (ADR 0111 decision 6): the scope's
     * current-epoch key sealed to the identity the publish installs, signed by it — so the account's grant row names the
     * caller as its granter and the NEW identity as the key that signed it (ADR 0028 Decision 6: the reads
     * serve that copy). A bulk update by `(user, scope)`, never a write onto an entity this transaction may hold from
     * before the scope's lock. In the publish's transaction, under that scope's row lock.
     */
    fun applySelfGrant(userId: String, scopeId: String, grant: StagedSelfGrantRow, identityPublicKey: String): Int =
        MembershipEntity.update(
            "wrappedGrant = ?1, grantedEpoch = ?2, granterUserId = ?3, granterIdentityPublicKey = ?4, grantSignature = ?5 " +
                "where userId = ?3 and scopeId = ?6",
            grant.wrappedGrant, grant.epoch, userId, identityPublicKey, grant.grantSignature, scopeId,
        )

    /** Replace [userId]'s pending re-key rows (ADR 0111 decision 8) with one per scope of [epochs] — a later rotation
     *  of the account supersedes every row an earlier one wrote. */
    fun replaceRegrants(userId: String, epochs: Map<String, Int>) {
        RotationRegrantEntity.delete("userId", userId)
        epochs.forEach { (scopeId, epoch) ->
            RotationRegrantEntity().apply {
                id = UUID.randomUUID().toString()
                this.userId = userId
                this.scopeId = scopeId
                epochAtRotation = epoch
            }.persist()
        }
    }

    private fun wrapOf(userId: String, method: WrapMethod): MasterKeyWrapEntity? =
        MasterKeyWrapEntity.find("userId = ?1 and method = ?2", userId, method.name).firstResult()
}
