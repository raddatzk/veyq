package me.raddatz.veyq.core.moderation.statement

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicUpdate
import java.time.Instant

/** Why a recipient is told (ADR 0093 § 7) — the wire's `ModerationStatement.roles`. */
enum class StatementRole { SHARE_CREATOR, UPLOADER, SCOPE_OWNER }

/** Whom a recipient row names: a member's account, or a guest identity (told nothing in veyq, it has no account). */
enum class RecipientKind { ACCOUNT, GUEST }

/** What led to a measure: a report, or an authority's order (ADR 0093 § 12, which no path takes yet). */
enum class StatementBasis { REPORT, ORDER }

/** The ground a measure rests on: a report category's value — the law, or the terms (`TERMS_BREACH`). */
enum class StatementGround { CHILD_SEXUAL_ABUSE, TERRORIST_CONTENT, THREAT_TO_LIFE, ILLEGAL_CONTENT, TERMS_BREACH }

/** What became of an objection. */
enum class ObjectionOutcome { UPHELD, LIFTED }

/** A measure's statement of reasons (`veyq_moderation_statement`); see the migration for each column. */
@Entity
@Table(name = "veyq_moderation_statement")
@DynamicUpdate
class ModerationStatementEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "measure_id", nullable = false, columnDefinition = "uuid")
    lateinit var measureId: String

    @Column(name = "case_id", nullable = false, columnDefinition = "uuid")
    lateinit var caseId: String

    @Column(name = "ground", nullable = false, length = 24)
    lateinit var ground: String

    @Column(name = "basis", nullable = false, length = 8)
    lateinit var basis: String

    /** The epoch [sealedFacts] is sealed to; null without facts. */
    @Column(name = "epoch")
    var epoch: Int? = null

    /** The facts sealed to the moderation key (`moderation-note` [epoch, caseId]); null without facts. */
    @Column(name = "sealed_facts", columnDefinition = "TEXT")
    var sealedFacts: String? = null

    /** When it went out; null where nobody could be told — the hold then counts from the decision. */
    @Column(name = "sent_at")
    var sentAt: Instant? = null

    companion object : PanacheCompanionBase<ModerationStatementEntity, String>
}

/** One recipient of a statement (`veyq_moderation_statement_recipient`). */
@Entity
@Table(name = "veyq_moderation_statement_recipient")
@DynamicUpdate
class StatementRecipientEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "statement_id", nullable = false, columnDefinition = "uuid")
    lateinit var statementId: String

    @Column(name = "recipient_kind", nullable = false, length = 8)
    lateinit var recipientKind: String

    @Column(name = "recipient_id", nullable = false, length = 64)
    lateinit var recipientId: String

    /** Comma-joined [StatementRole] names. */
    @Column(name = "roles", nullable = false, length = 64)
    lateinit var roles: String

    /** The facts sealed to this member's identity (`moderation-facts` [caseId, accountId]); null without facts or for a guest. */
    @Column(name = "sealed_facts", columnDefinition = "TEXT")
    var sealedFacts: String? = null

    /** When the instance mail telling this member went out; null until it did. */
    @Column(name = "mailed_at")
    var mailedAt: Instant? = null

    val roleSet: List<StatementRole> get() = roles.split(',').filter { it.isNotEmpty() }.map { StatementRole.valueOf(it) }

    companion object : PanacheCompanionBase<StatementRecipientEntity, String>
}

/** The one objection to a statement's measure (`veyq_moderation_objection`). */
@Entity
@Table(name = "veyq_moderation_objection")
@DynamicUpdate
class ModerationObjectionEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "statement_id", nullable = false, columnDefinition = "uuid")
    lateinit var statementId: String

    @Column(name = "case_id", nullable = false, columnDefinition = "uuid")
    lateinit var caseId: String

    @Column(name = "objector_account", nullable = false, columnDefinition = "uuid")
    lateinit var objectorAccount: String

    @Column(name = "epoch", nullable = false)
    var epoch: Int = 0

    @Column(name = "sealed_objection", nullable = false, columnDefinition = "TEXT")
    lateinit var sealedObjection: String

    @Column(name = "received_at", nullable = false)
    lateinit var receivedAt: Instant

    @Column(name = "decided_at")
    var decidedAt: Instant? = null

    @Column(name = "decided_by", columnDefinition = "uuid")
    var decidedBy: String? = null

    @Column(name = "outcome", length = 8)
    var outcome: String? = null

    @Column(name = "own_review", nullable = false)
    var ownReview: Boolean = false

    val open: Boolean get() = decidedAt == null

    companion object : PanacheCompanionBase<ModerationObjectionEntity, String> {
        /** Whether [caseId] has an objection still waiting for its decision — a case does not close then (ADR 0093 § 11). */
        fun openOn(caseId: String): Boolean = count("caseId = ?1 and decidedAt is null", caseId) > 0
    }
}
