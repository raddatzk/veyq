package me.raddatz.veyq.core.moderation

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicUpdate
import java.time.Instant

/** Why an epoch was minted (ADR 0099 § 4; INITIAL is epoch 1, when the Server Owner turned moderation on). */
enum class MintReason { INITIAL, REMOVAL, IDENTITY_ROTATION, COMPROMISE, BY_HAND }

/**
 * One epoch of the instance moderation key: its public bundle only — the server never holds `M_n` (ADR 0099 § 2).
 * [rotationDueAt] is set on the current epoch when one of its holders leaves or rotates their identity (owner decision
 * Q3 (a): reports keep sealing to it until the Server Owner mints the next).
 */
@Entity
@Table(name = "veyq_moderation_epoch")
@DynamicUpdate
class ModerationEpochEntity : PanacheEntityBase {
    @Id
    @Column(name = "epoch")
    var epoch: Int = 0

    @Column(name = "kem_public_key", nullable = false, columnDefinition = "TEXT")
    lateinit var kemPublicKey: String

    @Column(name = "sign_public_key", nullable = false, length = 64)
    lateinit var signPublicKey: String

    @Column(name = "reason", nullable = false, length = 24)
    lateinit var reason: String

    @Column(name = "rotation_due_at")
    var rotationDueAt: Instant? = null

    @Column(name = "rotation_due_mailed_at")
    var rotationDueMailedAt: Instant? = null

    companion object : PanacheCompanionBase<ModerationEpochEntity, Int>
}

/** `M_n` sealed to one moderator's identity (`moderation-grant` [epoch]) — opaque to the server. */
@Entity
@Table(name = "veyq_moderation_grant")
@DynamicUpdate
class ModerationGrantEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "moderator_id", columnDefinition = "uuid", nullable = false)
    lateinit var moderatorId: String

    @Column(name = "epoch", nullable = false)
    var epoch: Int = 0

    @Column(name = "sealed_grant", nullable = false, columnDefinition = "TEXT")
    lateinit var sealedGrant: String

    @Column(name = "delivered_by", columnDefinition = "uuid", nullable = false)
    lateinit var deliveredBy: String

    companion object : PanacheCompanionBase<ModerationGrantEntity, String>
}

/** A key proof's challenge: single use, bound to the account it was issued to. */
@Entity
@Table(name = "veyq_moderation_challenge")
@DynamicUpdate
class ModerationChallengeEntity : PanacheEntityBase {
    @Id
    @Column(name = "challenge", length = 64)
    lateinit var challenge: String

    @Column(name = "account_id", columnDefinition = "uuid", nullable = false)
    lateinit var accountId: String

    @Column(name = "expires_at", nullable = false)
    lateinit var expiresAt: Instant

    companion object : PanacheCompanionBase<ModerationChallengeEntity, String>
}

/** One row of the moderation log (ADR 0093 § 11) — append-only; see [ModerationLog]. */
@Entity
@Table(name = "veyq_moderation_event")
@DynamicUpdate
class ModerationEventEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "event", nullable = false, length = 24)
    lateinit var event: String

    @Column(name = "occurred_at", nullable = false)
    lateinit var occurredAt: Instant

    @Column(name = "moderator_id", columnDefinition = "uuid")
    var moderatorId: String? = null

    @Column(name = "actor_id", columnDefinition = "uuid")
    var actorId: String? = null

    @Column(name = "case_id", columnDefinition = "uuid")
    var caseId: String? = null

    @Column(name = "epoch")
    var epoch: Int? = null

    @Column(name = "reason", length = 24)
    var reason: String? = null

    @Column(name = "sealed_text", columnDefinition = "TEXT")
    var sealedText: String? = null

    @Column(name = "expires_at")
    var expiresAt: Instant? = null

    companion object : PanacheCompanionBase<ModerationEventEntity, String>
}
