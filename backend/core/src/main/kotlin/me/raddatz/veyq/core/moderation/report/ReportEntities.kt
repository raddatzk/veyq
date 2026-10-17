package me.raddatz.veyq.core.moderation.report

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicUpdate
import java.time.Instant

/**
 * What a report says the content is (ADR 0093 § 1), **most urgent first**: a case carries the most urgent category of
 * its reports, and the first three are mailed at once ([urgent]), the rest in the day's bundle (owner decision Q7).
 */
enum class ReportCategory(val urgent: Boolean) {
    CHILD_SEXUAL_ABUSE(true),
    TERRORIST_CONTENT(true),
    THREAT_TO_LIFE(true),
    ILLEGAL_CONTENT(false),
    TERMS_BREACH(false),
    ;

    /** The more urgent of this and [other]. */
    fun mostUrgent(other: ReportCategory): ReportCategory = if (other.ordinal < ordinal) other else this
}

/** What a reported item is (the wire's `kind`). A LINK's id is its share id. */
enum class ReportItemKind { NODE, ITEM, LINK }

/** A case's state. Only [OPEN] is reached until the review lands (cr-moderation-review-and-measures closes cases). */
enum class CaseState { OPEN, CLOSED }

/** One report (`veyq_moderation_report`): the clear fields and the sealed part. No foreign key into a space. */
@Entity
@Table(name = "veyq_moderation_report")
@DynamicUpdate
class ModerationReportEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "epoch", nullable = false)
    var epoch: Int = 0

    @Column(name = "received_at", nullable = false)
    lateinit var receivedAt: Instant

    @Column(name = "category", nullable = false, length = 24)
    lateinit var category: String

    @Column(name = "sealed_report", nullable = false, columnDefinition = "TEXT")
    lateinit var sealedReport: String

    /** The reporting member's account; null for a guest. What the cap on open reports counts (owner decision Q8). */
    @Column(name = "reporter_account", columnDefinition = "uuid")
    var reporterAccount: String? = null

    /** The link a guest reported through; null for a member. */
    @Column(name = "reporter_share", length = 64)
    var reporterShare: String? = null

    companion object : PanacheCompanionBase<ModerationReportEntity, String>
}

/** One case per reported item (`veyq_moderation_case`); [openKey] holds "one open case per item" in the database. */
@Entity
@Table(name = "veyq_moderation_case")
@DynamicUpdate
class ModerationCaseEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "item_kind", nullable = false, length = 8)
    lateinit var itemKind: String

    @Column(name = "item_id", nullable = false, length = 64)
    lateinit var itemId: String

    @Column(name = "scope_id", nullable = false, columnDefinition = "uuid")
    lateinit var scopeId: String

    @Column(name = "category", nullable = false, length = 24)
    lateinit var category: String

    @Column(name = "state", nullable = false, length = 16)
    lateinit var state: String

    /** "KIND:id" while [state] is OPEN, null once closed. */
    @Column(name = "open_key", length = 80)
    var openKey: String? = null

    companion object : PanacheCompanionBase<ModerationCaseEntity, String> {
        fun openKeyOf(kind: ReportItemKind, itemId: String) = "${kind.name}:$itemId"
    }
}

/** What one report hands over for one case's item: its keys sealed under that report's `K_report`. */
@Entity
@Table(name = "veyq_report_share")
@DynamicUpdate
class ReportShareEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "report_id", nullable = false, columnDefinition = "uuid")
    lateinit var reportId: String

    @Column(name = "case_id", nullable = false, columnDefinition = "uuid")
    lateinit var caseId: String

    @Column(name = "sealed_keys", nullable = false, columnDefinition = "TEXT")
    lateinit var sealedKeys: String

    companion object : PanacheCompanionBase<ReportShareEntity, String>
}
