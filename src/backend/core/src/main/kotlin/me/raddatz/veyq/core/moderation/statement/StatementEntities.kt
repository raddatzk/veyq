package me.raddatz.veyq.core.moderation.statement

import me.raddatz.veyq.core.moderation.report.CaseOrigin
import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicUpdate
import java.time.Instant

/**
 * Why a recipient is told (ADR 0093 § 7) — the wire's `ModerationStatement.roles` for a moderator's measure;
 * [ACCOUNT_HOLDER] and [TENANT_OWNER] name the recipients of a Server Owner's own step, which only a mail reaches.
 */
enum class StatementRole { SHARE_CREATOR, UPLOADER, SCOPE_OWNER, ACCOUNT_HOLDER, TENANT_OWNER }

/**
 * Whom a recipient row names: a member's account, or a guest identity — told on the link it came through, never by mail,
 * it has no account (ADR 0093 §§ 7, 12; [me.raddatz.veyq.core.moderation.order.GuestNotices]).
 */
enum class RecipientKind { ACCOUNT, GUEST }

/**
 * What led to a measure (ADR 0093 § 7, DSA Art. 17(3)(b): an Art. 16 notice, or the host's own initiative): [REPORT], a
 * measure the host took on a case a report opened; [ORDER], an authority's removal order's own block — written only by
 * the order ([AuthorityOrderHandler][me.raddatz.veyq.core.moderation.order.AuthorityOrderHandler]); [OWN_FINDING], the
 * host's own initiative — every other measure: a Server Owner's step on no case, and a moderator's or a Server Owner's
 * measure on a case an authority's order opened (inbox #820; an order is no Art. 16 notice, and a confidential order —
 * e-Evidence, §§ 100j–100k StPO — is never revealed by the statement).
 */
enum class StatementBasis {
    REPORT, ORDER, OWN_FINDING;

    companion object {
        /**
         * The basis of a measure the host takes — a moderator's or a Server Owner's, never an order's own block — on a
         * case opened as [origin] (`ModerationCaseEntity.origin`; null for a step on no case): a report's case reads
         * [REPORT], everything else the host's own initiative — a case an order opened too. A restored case's stub
         * ([CaseOrigin.RESTORED]) reads as a report's, as it did.
         */
        fun ofCase(origin: String?): StatementBasis = when (origin) {
            null, CaseOrigin.ORDER.name -> OWN_FINDING
            else -> REPORT
        }
    }
}

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

    /** The case; null for a Server Owner's step over content that answers none (ADR 0093 § 7). */
    @Column(name = "case_id", columnDefinition = "uuid")
    var caseId: String? = null

    @Column(name = "ground", nullable = false, length = 24)
    lateinit var ground: String

    /** The provision of the law or the clause of the terms the ground relies on (DSA Art. 17(3)(d), (e)); null for none. */
    @Column(name = "provision", length = 200)
    var provision: String? = null

    @Column(name = "basis", nullable = false, length = 16)
    lateinit var basis: String

    /** The epoch [sealedFacts] is sealed to; null without facts. */
    @Column(name = "epoch")
    var epoch: Int? = null

    /**
     * The facts sealed to the moderation key — a moderator's (`moderation-note` [epoch, caseId]) or, for a Server Owner's
     * step, theirs (`moderation-step-facts` [epoch, id]); null without facts.
     */
    @Column(name = "sealed_facts", columnDefinition = "TEXT")
    var sealedFacts: String? = null

    /**
     * A moderator's facts sealed once for the affected, under the statement's `K_facts` (`moderation-statement-facts`
     * [caseId, id]) — each member's [StatementRecipientEntity.wrappedFactsKey] opens it; null without facts, and for a
     * Server Owner's step, whose facts go by mail.
     */
    @Column(name = "sealed_member_facts", columnDefinition = "TEXT")
    var sealedMemberFacts: String? = null

    /** When it went out; null where nobody could be told — the hold then counts from the decision — or while deferred. */
    @Column(name = "sent_at")
    var sentAt: Instant? = null

    /** While an authority's deferral holds it back (sent_at null), when it goes out; null otherwise. */
    @Column(name = "deferred_until")
    var deferredUntil: Instant? = null

    companion object : PanacheCompanionBase<ModerationStatementEntity, String>
}

/**
 * An authority's request to defer a statement (`veyq_moderation_deferral`, TCO Art. 11(3), ADR 0093 § 7): the first and
 * at most one [renewal]. Its reason is sealed to the moderation key ([epoch], [sealedReason]) or is the reference of the
 * order it came with ([orderId]).
 */
@Entity
@Table(name = "veyq_moderation_deferral")
@DynamicUpdate
class StatementDeferralEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "statement_id", nullable = false, columnDefinition = "uuid")
    lateinit var statementId: String

    @Column(name = "case_id", nullable = false, columnDefinition = "uuid")
    lateinit var caseId: String

    @Column(name = "order_id", columnDefinition = "uuid")
    var orderId: String? = null

    @Column(name = "epoch")
    var epoch: Int? = null

    @Column(name = "sealed_reason", columnDefinition = "TEXT")
    var sealedReason: String? = null

    @Column(name = "entered_by", nullable = false, columnDefinition = "uuid")
    lateinit var enteredBy: String

    @Column(name = "renewal", nullable = false)
    var renewal: Boolean = false

    companion object : PanacheCompanionBase<StatementDeferralEntity, String>
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

    /**
     * The statement's `K_facts` wrapped to this member's identity (`moderation-facts-key` [caseId, statementId,
     * accountId]); null without facts or for a guest.
     */
    @Column(name = "wrapped_facts_key", columnDefinition = "TEXT")
    var wrappedFactsKey: String? = null

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
