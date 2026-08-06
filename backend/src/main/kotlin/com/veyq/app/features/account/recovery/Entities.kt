package com.veyq.app.features.account.recovery

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * A one-time password-recovery token (cr-account-recovery, ADR 0054).
 *
 * Only the SHA-256 token hash is stored — never the token (the ADR 0004 model, and the same shape as
 * `TenantInviteEntity`). A live row is **not** a second factor: it only buys the release of the RECOVERY wrap,
 * which is opened by the Secret Key alone. Whoever holds this row — including the operator — still faces
 * ciphertext they cannot open, and the completing reset is gated on reproducing `recovery_master_key_tag`.
 *
 * [email] is kept next to [userId] because it is the address actually mailed: an account's address can change,
 * and a token must not stay redeemable against an address it was never sent to.
 */
@Entity
@Table(name = "veyq_account_recovery")
class AccountRecoveryEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", length = 36)
    lateinit var id: String

    @Column(name = "user_id", nullable = false, length = 36)
    lateinit var userId: String

    @Column(name = "email", nullable = false, length = 254)
    lateinit var email: String

    @Column(name = "token_hash", nullable = false, length = 64)
    lateinit var tokenHash: String

    @Column(name = "status", nullable = false, length = 16)
    lateinit var status: String

    @Column(name = "expires_at", nullable = false)
    lateinit var expiresAt: Instant

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    companion object : PanacheCompanionBase<AccountRecoveryEntity, String> {
        /** Mintable and redeemable. At most one per user at a time — a fresh request supersedes the last. */
        const val PENDING = "PENDING"

        /** Spent by a completed reset. Kept until purge so a replay reads as spent, not unknown. */
        const val CONSUMED = "CONSUMED"

        /** Replaced by a newer request for the same account. */
        const val SUPERSEDED = "SUPERSEDED"

        /** Past its TTL, flipped by the scheduled sweep. */
        const val EXPIRED = "EXPIRED"

        val TERMINAL = listOf(CONSUMED, SUPERSEDED, EXPIRED)
    }
}
