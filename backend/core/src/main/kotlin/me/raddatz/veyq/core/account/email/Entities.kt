package me.raddatz.veyq.core.account.email

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import org.hibernate.annotations.DynamicUpdate

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
@DynamicUpdate
class EmailConfirmationEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
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

/**
 * A pending change of the sign-in address (uc.account.change-email, ADR 0074 Addendum): the address asked for and
 * the auth public key the client derived under its salt, installed together by the confirmation. At most one per
 * account (`ux_email_change_user`): a newer request replaces the row. Spent by **deleting** it — a compare-and-set
 * by row count — so a used link, an unknown one and a replaced one read alike; an expired one is purged by the
 * confirmation purge's schedule.
 *
 * Only the SHA-256 of the mailed token is stored, so a dump yields no link that confirms. [newEmail] is the address
 * as asked for; the swap normalizes it as every stored address is.
 */
@Entity
@Table(name = "veyq_email_change")
@DynamicUpdate
class EmailChangeEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    lateinit var userId: String

    @Column(name = "new_email", nullable = false, length = 254)
    lateinit var newEmail: String

    @Column(name = "auth_public_key", nullable = false, columnDefinition = "TEXT")
    lateinit var authPublicKey: String

    @Column(name = "token_hash", nullable = false, length = 64)
    lateinit var tokenHash: String

    @Column(name = "expires_at", nullable = false)
    lateinit var expiresAt: Instant

    companion object : PanacheCompanionBase<EmailChangeEntity, String>
}
