package com.veyq.app.features.account.email

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * A one-time address-confirmation token (ADR 0074 Decision 4) — the recovery token's lifecycle
 * (`AccountRecoveryEntity`), copied on purpose.
 *
 * Only the SHA-256 token hash is stored, so a database dump yields no token that confirms anything. That is
 * hygiene, not a boundary against the operator, who can write `veyq_user.email_confirmed_at` directly — which
 * matters to nothing, because nothing is gated on it (Decision 7).
 *
 * [email] is the address the link was **mailed to**, kept next to [userId] because the record is bound to the
 * address it confirmed: a token must not confirm an address it was never sent to (Decision 1, 5).
 */
@Entity
@Table(name = "veyq_email_confirmation")
class EmailConfirmationEntity : PanacheEntityBase {
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

    companion object : PanacheCompanionBase<EmailConfirmationEntity, String> {
        /** Mailed and redeemable. At most one per account — a fresh request supersedes the last. */
        const val PENDING = "PENDING"

        /** Spent by a confirmation. Kept until purge so a replay reads as spent, not unknown. */
        const val CONSUMED = "CONSUMED"

        /** Replaced by a newer request, or compensated after a send that never left (ADR 0055 §6). */
        const val SUPERSEDED = "SUPERSEDED"

        /** Past its TTL, flipped by the scheduled sweep. */
        const val EXPIRED = "EXPIRED"

        /** Every state the purge deletes after retention. Nothing is ever minted from a token, so none strands. */
        val TERMINAL = listOf(CONSUMED, SUPERSEDED, EXPIRED)
    }
}
