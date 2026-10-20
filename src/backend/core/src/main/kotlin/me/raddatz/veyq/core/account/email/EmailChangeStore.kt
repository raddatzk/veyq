package me.raddatz.veyq.core.account.email

import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.UUID

/** Persistence for pending changes of the sign-in address (uc.account.change-email). */
@ApplicationScoped
class EmailChangeStore {

    /**
     * Store [userId]'s pending change, replacing any earlier one, so an older link cannot still install its address
     * after the person asked again. Runs in the caller's transaction, which holds the account's lock: two requests of
     * one account are serialized there, so the delete-then-insert never meets the per-account unique index.
     * Returns the new row's id, for the send's compensation.
     */
    fun replace(userId: String, newEmail: String, authPublicKey: String, tokenHash: String, expiresAt: Instant): String {
        EmailChangeEntity.delete("userId", userId)
        val id = UUID.randomUUID().toString()
        EmailChangeEntity().apply {
            this.id = id
            this.userId = userId
            this.newEmail = newEmail
            this.authPublicKey = authPublicKey
            this.tokenHash = tokenHash
            this.expiresAt = expiresAt
        }.persist()
        return id
    }

    /** Withdraw a change whose mail never left (ADR 0055 §6) — a compensation in its own transaction. */
    @Transactional
    fun withdraw(id: String) {
        EmailChangeEntity.delete("id", id)
    }

    /** The pending change behind a presented token, or null. */
    fun findByTokenHash(tokenHash: String): EmailChangeEntity? = EmailChangeEntity.find("tokenHash", tokenHash).firstResult()

    /**
     * Spend the change — a **compare-and-set** by row count (developer-styleguide §12): the delete of a row that is
     * still there and still live. Two confirmations that both read it cannot both win; the loser deleted nothing.
     */
    fun spend(id: String, now: Instant): Boolean = EmailChangeEntity.delete("id = ?1 and expiresAt > ?2", id, now) == 1L

    /** Delete changes past their expiry (the confirmation purge's schedule). Nothing else references a row. */
    @Transactional
    fun purgeExpired(now: Instant): Long = EmailChangeEntity.delete("expiresAt < ?1", now)
}
