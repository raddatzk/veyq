package com.veyq.app.features.account.email

import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.UUID

/** Persistence for address-confirmation tokens (ADR 0074 Decision 4) — the `RecoveryStore` token half. */
@ApplicationScoped
class EmailConfirmationStore {

    /** Mint a token for [email], superseding the outstanding one for that account, so an older mail cannot still
     *  confirm after the person asked again. Returns the new row's id, for the send's compensation. */
    @Transactional
    fun mint(userId: String, email: String, tokenHash: String, expiresAt: Instant): String {
        EmailConfirmationEntity.update(
            "status = ?1 where userId = ?2 and status = ?3",
            EmailConfirmationEntity.SUPERSEDED, userId, EmailConfirmationEntity.PENDING,
        )
        val id = UUID.randomUUID().toString()
        EmailConfirmationEntity().apply {
            this.id = id
            this.userId = userId
            this.email = email
            this.tokenHash = tokenHash
            this.status = EmailConfirmationEntity.PENDING
            this.expiresAt = expiresAt
            this.createdAt = Instant.now()
        }.persist()
        return id
    }

    /** Kill a token whose mail never left (ADR 0055 §6): a soft revoke, so its secret is dead at once and the
     *  purge still finds it. A compensation in its own transaction, never a rollback of the committed mint. */
    @Transactional
    fun supersede(id: String) {
        EmailConfirmationEntity.update(
            "status = ?1 where id = ?2 and status = ?3",
            EmailConfirmationEntity.SUPERSEDED, id, EmailConfirmationEntity.PENDING,
        )
    }

    /** The row behind a presented token, or null. Liveness is [isLive]'s job: the caller answers unknown, expired,
     *  superseded and spent alike. */
    fun findByTokenHash(tokenHash: String): EmailConfirmationEntity? =
        EmailConfirmationEntity.find("tokenHash", tokenHash).firstResult()

    fun isLive(row: EmailConfirmationEntity, now: Instant): Boolean =
        row.status == EmailConfirmationEntity.PENDING && row.expiresAt.isAfter(now)

    /**
     * Spend the token — a **compare-and-set**: only a row still `PENDING` flips, and the caller confirms only if
     * exactly one did. Two confirmations racing on one token cannot both win, which the invite's plain status
     * update (`TenantStore.consumeInvite`) does not promise.
     */
    fun consume(id: String): Boolean = EmailConfirmationEntity.update(
        "status = ?1 where id = ?2 and status = ?3",
        EmailConfirmationEntity.CONSUMED, id, EmailConfirmationEntity.PENDING,
    ) == 1

    // --- scheduled sweep (see EmailConfirmationPurge) ---

    /** Flip overdue tokens to EXPIRED so a replay reads as spent rather than live. */
    @Transactional
    fun expireOverdue(now: Instant): Long = EmailConfirmationEntity.update(
        "status = ?1 where status = ?2 and expiresAt < ?3",
        EmailConfirmationEntity.EXPIRED, EmailConfirmationEntity.PENDING, now,
    ).toLong()

    /** Delete terminal rows older than the retention window. */
    @Transactional
    fun purgeTerminal(before: Instant): Long = EmailConfirmationEntity.delete(
        "status in ?1 and createdAt < ?2", EmailConfirmationEntity.TERMINAL, before,
    )
}
