package com.veyq.app.shared.identity

import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.UUID

/** One Master Key wrap to persist with a new account (opaque ciphertext + KDF params). */
data class WrapData(val method: String, val wrappedKey: String, val kdfParams: String?)

/**
 * Creates operator-blind user accounts — the **one** place a `veyq_user` + its Master Key wraps are written,
 * so the invariant (only ciphertext + public keys + metadata, never a password or content key) is audited in
 * a single spot. Used by first-run setup (the Server Owner) and by invite-gated registration (a tenant owner,
 * cr-0010). The setup single-owner lock is *not* this store's concern — its caller writes it.
 */
@ApplicationScoped
class AccountStore {

    fun findByEmail(email: String): VeyqUser? = VeyqUser.find("email", email).firstResult()

    /** Persist a new account (identity + wraps) with the given server role. Returns the new user id. */
    @Transactional
    fun createAccount(
        email: String,
        serverRole: String,
        suiteId: String,
        identityPublicKey: String,
        wrappedIdentityPrivateKey: String,
        authPublicKey: String,
        recoveryMasterKeyTag: String?,
        wraps: List<WrapData>,
    ): String {
        val userId = UUID.randomUUID().toString()
        VeyqUser().apply {
            id = userId
            this.email = email
            this.serverRole = serverRole
            this.suiteId = suiteId
            this.identityPublicKey = identityPublicKey
            this.wrappedIdentityPrivateKey = wrappedIdentityPrivateKey
            this.authPublicKey = authPublicKey
            this.recoveryMasterKeyTag = recoveryMasterKeyTag
            createdAt = Instant.now()
        }.persist()

        wraps.forEach { wrap ->
            MasterKeyWrapEntity().apply {
                id = UUID.randomUUID().toString()
                this.userId = userId
                method = wrap.method
                wrappedKey = wrap.wrappedKey
                kdfParams = wrap.kdfParams
            }.persist()
        }
        return userId
    }
}
