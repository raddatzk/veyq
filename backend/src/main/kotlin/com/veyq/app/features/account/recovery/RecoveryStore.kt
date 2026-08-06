package com.veyq.app.features.account.recovery

import com.veyq.app.shared.identity.MasterKeyWrapEntity
import com.veyq.app.shared.identity.VeyqUser
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.UUID

/** The account facts a release needs, all ciphertext or a public descriptor. */
data class RecoveryMaterialRow(
    /** The account's own address — the source of its deterministic auth salt (ADR 0003), which the reset needs
     *  to derive the new auth keypair. Not the row's mailed address: the salt is derived from `veyq_user.email`,
     *  so that is the value the client must reproduce. */
    val email: String,
    val suiteId: String,
    val wrappedKey: String,
    val kdfParams: String?,
    val wrappedIdentityPrivateKey: String,
)

/**
 * Persistence for password recovery (ADR 0054).
 *
 * Two responsibilities that deliberately live together: the one-time token lifecycle, and the single
 * all-or-nothing reset. The reset is one method rather than a handler orchestrating four store calls, because
 * its atomicity **is** the requirement — a partial apply produces an account that logs in but cannot unlock, or
 * unlocks but cannot log in, and both are lockouts.
 */
@ApplicationScoped
class RecoveryStore {

    /** Resolve an account by its login address. Returns null for an unknown address — the *caller* must make
     *  that indistinguishable to the client (non-enumerability lives in the handler's response shape). */
    @Transactional
    fun findUserByEmail(email: String): VeyqUser? =
        VeyqUser.find("email", email).firstResult()

    /** Mint a token, superseding any outstanding one for that account: one live recovery per user at a time,
     *  so an older mail cannot still be redeemed after the person asked again. */
    @Transactional
    fun mint(userId: String, email: String, tokenHash: String, expiresAt: Instant) {
        AccountRecoveryEntity.update(
            "status = ?1 where userId = ?2 and status = ?3",
            AccountRecoveryEntity.SUPERSEDED, userId, AccountRecoveryEntity.PENDING,
        )
        AccountRecoveryEntity().apply {
            this.id = UUID.randomUUID().toString()
            this.userId = userId
            this.email = email
            this.tokenHash = tokenHash
            this.status = AccountRecoveryEntity.PENDING
            this.expiresAt = expiresAt
            this.createdAt = Instant.now()
        }.persist()
    }

    /** The row behind a presented token, or null. Liveness is [isLive]'s job, not this lookup's — the caller
     *  must not be able to distinguish "unknown" from "expired" from "spent" in its response. */
    @Transactional
    fun findByTokenHash(tokenHash: String): AccountRecoveryEntity? =
        AccountRecoveryEntity.find("tokenHash", tokenHash).firstResult()

    fun isLive(row: AccountRecoveryEntity, now: Instant): Boolean =
        row.status == AccountRecoveryEntity.PENDING && row.expiresAt.isAfter(now)

    /**
     * The RECOVERY wrap plus what it protects.
     *
     * This reads `MasterKeyWrapEntity` directly rather than going through `KeyMaterialStore.find`, and that is
     * deliberate: `KeyMaterialStore.find` returns **all** wraps unfiltered, and the RECOVERY one is stripped a
     * level up in `KeyMaterialHandler.forUser`. Reusing it here would mean a second caller of an unfiltered
     * read, which is exactly the trap ADR 0054 § Consequences names. Selecting the one method this flow is
     * entitled to keeps the blast radius at one row.
     */
    @Transactional
    fun recoveryMaterial(userId: String): RecoveryMaterialRow? {
        val user = VeyqUser.findById(userId) ?: return null
        val wrap = MasterKeyWrapEntity
            .find("userId = ?1 and method = ?2", userId, RECOVERY_METHOD)
            .firstResult() ?: return null
        return RecoveryMaterialRow(user.email, user.suiteId, wrap.wrappedKey, wrap.kdfParams, user.wrappedIdentityPrivateKey)
    }

    /** The commitment written at account birth. Null means the account predates it or was written wrong — an
     *  account that cannot prove possession must not be resettable, so the caller treats null as a refusal. */
    @Transactional
    fun recoveryTag(userId: String): String? =
        VeyqUser.findById(userId)?.recoveryMasterKeyTag

    /**
     * Apply the reset. **One transaction, all four writes or none** (ADR 0054 § Decision 3).
     *
     * Deleting `DEVICE_KEY` alongside `DEVICE` is the load-bearing part: a `DEVICE` wrap mixes the old password
     * and would merely fail, but a `DEVICE_KEY` wrap takes no password at all and would keep granting
     * password-less entry to a device the person may be resetting *because of*.
     *
     * Returns false if the account lost its PASSWORD floor between release and completion (it cannot, through
     * any HTTP surface — `deleteWrap` whitelists the device methods — but a reset that silently created a
     * second floor would be worse than one that refuses).
     */
    @Transactional
    fun applyReset(userId: String, wrappedKey: String, kdfParams: String, authPublicKey: String, tokenId: String): Boolean {
        val user = VeyqUser.findById(userId) ?: return false
        val floor = MasterKeyWrapEntity
            .find("userId = ?1 and method = ?2", userId, PASSWORD_METHOD)
            .firstResult() ?: return false

        floor.wrappedKey = wrappedKey
        floor.kdfParams = kdfParams
        user.authPublicKey = authPublicKey

        MasterKeyWrapEntity.delete("userId = ?1 and method in ?2", userId, DEVICE_METHODS)
        AccountRecoveryEntity.update("status = ?1 where id = ?2", AccountRecoveryEntity.CONSUMED, tokenId)
        return true
    }

    // --- scheduled sweep (see RecoveryPurge) ---

    /** Flip overdue tokens to EXPIRED so a replay reads as spent rather than live. */
    @Transactional
    fun expireOverdue(now: Instant): Long = AccountRecoveryEntity.update(
        "status = ?1 where status = ?2 and expiresAt < ?3",
        AccountRecoveryEntity.EXPIRED, AccountRecoveryEntity.PENDING, now,
    ).toLong()

    /** Delete terminal rows older than the retention window. */
    @Transactional
    fun purgeTerminal(before: Instant): Long = AccountRecoveryEntity.delete(
        "status in ?1 and createdAt < ?2", AccountRecoveryEntity.TERMINAL, before,
    )

    private companion object {
        private const val RECOVERY_METHOD = "RECOVERY"
        private const val PASSWORD_METHOD = "PASSWORD"
        private val DEVICE_METHODS = listOf("DEVICE", "DEVICE_KEY")
    }
}
