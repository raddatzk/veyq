package me.raddatz.veyq.core.identity

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicUpdate
import java.time.Instant

/**
 * A moderator: an existing account the Server Owner appointed for the whole instance (ADR 0099 § 1). Here, in the
 * kernel, because the session's identity reads it: an accepted, unremoved row is the account's [MODERATOR] role, the
 * second role a session can carry and not a value of `veyq_user.server_role` (ADR 0099 § Rejected: one column holds
 * one role, and the Server Owner is a moderator too). Everything else about moderation — the key's epochs and grants,
 * the proof, the log — is the `core.moderation` slice's.
 *
 * The `code*` and `confirmed*` columns confirm the moderator's address for moderation notices (ADR 0097 decision 5),
 * as `veyq_instance_mail_settings` does for the Server Owner's.
 */
@Entity
@Table(name = "veyq_moderator")
@DynamicUpdate
class ModeratorEntity : PanacheEntityBase {
    @Id
    @Column(name = "account_id", columnDefinition = "uuid")
    lateinit var accountId: String

    @Column(name = "appointed_by", columnDefinition = "uuid", nullable = false)
    lateinit var appointedBy: String

    @Column(name = "appointed_at", nullable = false)
    lateinit var appointedAt: Instant

    @Column(name = "accepted_at")
    var acceptedAt: Instant? = null

    @Column(name = "removed_at")
    var removedAt: Instant? = null

    @Column(name = "code_hash", length = 64)
    var codeHash: String? = null

    @Column(name = "code_address", length = 254)
    var codeAddress: String? = null

    @Column(name = "code_expires_at")
    var codeExpiresAt: Instant? = null

    @Column(name = "code_attempts", nullable = false)
    var codeAttempts: Int = 0

    @Column(name = "code_failed", nullable = false)
    var codeFailed: Boolean = false

    @Column(name = "confirmed_address", length = 254)
    var confirmedAddress: String? = null

    @Column(name = "confirmed_at")
    var confirmedAt: Instant? = null

    /** On the roster: appointed and not removed (pending or accepted). */
    val onRoster: Boolean get() = removedAt == null

    /** A moderator: accepted and not removed — what the [MODERATOR] role means. */
    val accepted: Boolean get() = removedAt == null && acceptedAt != null

    companion object : PanacheCompanionBase<ModeratorEntity, String> {
        /** The session role an accepted, unremoved appointment carries (ADR 0099 § 1). */
        const val MODERATOR = "MODERATOR"

        /** Whether [accountId] is a moderator now — the one reading of the role. */
        fun isModerator(accountId: String): Boolean = findById(accountId)?.accepted == true
    }
}
