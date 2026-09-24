package com.veyq.app.features.account.rotation

import com.veyq.app.shared.identity.MasterKeyWrapEntity
import com.veyq.app.shared.identity.VeyqUser
import com.veyq.app.shared.identity.WrapMethod
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

/** One cold wrap as it arrives from the client: opaque ciphertext + its fresh KDF params. */
data class RewrittenWrap(val wrappedKey: String, val kdfParams: String)

/**
 * Persistence for key rotation after a compromise (ADR 0060, uc.account.rotate-keys).
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
     * Five writes, and each one is load-bearing:
     *  - the published `identityPublicKey` — from here every contact who pinned this account reads `changed`;
     *  - `wrappedIdentityPrivateKey` — the new identity private key under the **new** Master Key;
     *  - the `PASSWORD` and `RECOVERY` wraps, rewritten to hold the new Master Key under their existing
     *    secrets (a fresh salt each; the Emergency Kit survives because the *Secret Key* is unchanged);
     *  - `recoveryMasterKeyTag` — without it a later recovery proves possession of a key the account no longer
     *    has, and refuses;
     *  - the deletion of every **other** wrap.
     *
     * That deletion keeps [WrapMethod.SURVIVORS] and nothing else — a whitelist, shared with password change and
     * reset, and why it must be one is on [WrapMethod].
     *
     * Returns false when the account has no `PASSWORD` floor or no `RECOVERY` wrap to rewrite. Neither can
     * happen through any HTTP surface (`deleteWrap` whitelists the device methods, and every account is born
     * with both), but a rotation that silently *created* a floor would be worse than one that refuses: nothing
     * is written, and the caller keeps the account it had.
     */
    @Transactional
    fun applyRotation(
        userId: String,
        identityPublicKey: String,
        wrappedIdentityPrivateKey: String,
        passwordWrap: RewrittenWrap,
        recoveryWrap: RewrittenWrap,
        recoveryMasterKeyTag: String,
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
        user.recoveryMasterKeyTag = recoveryMasterKeyTag

        MasterKeyWrapEntity.delete("userId = ?1 and method not in ?2", userId, WrapMethod.SURVIVORS)
        return true
    }

    private fun wrapOf(userId: String, method: WrapMethod): MasterKeyWrapEntity? =
        MasterKeyWrapEntity.find("userId = ?1 and method = ?2", userId, method.name).firstResult()
}
