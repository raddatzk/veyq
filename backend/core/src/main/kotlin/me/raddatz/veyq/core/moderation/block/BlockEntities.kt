package me.raddatz.veyq.core.moderation.block

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicUpdate
import java.time.Instant

/**
 * What a measure blocks (ADR 0093 §§ 5-6, uc.moderation.take-a-measure). The wire's `CONTENT` is [CONTENT_NODE] or
 * [CONTENT_ITEM] by the kind of the case's item, and its `REPORTER` is [REPORTER_ACCOUNT] or [REPORTER_LINK] by who
 * reported; [MEMBER_SIGN_IN] is the Server Owner's alone.
 */
enum class BlockKind {
    SHARE, CONTENT_NODE, CONTENT_ITEM, GUEST_IDENTITY, MEMBER_SIGN_IN, REPORTER_ACCOUNT, REPORTER_LINK;

    /** The kind as the moderation surface names it (`ModerationMeasureKind`); null for the Server Owner's step. */
    val wire: String?
        get() = when (this) {
            SHARE -> "SHARE"
            CONTENT_NODE, CONTENT_ITEM -> "CONTENT"
            GUEST_IDENTITY -> "GUEST_IDENTITY"
            REPORTER_ACCOUNT, REPORTER_LINK -> "REPORTER"
            MEMBER_SIGN_IN -> null
        }

    /** The bars of a reporter: no step against what was reported, so they neither decide a case nor keep its hold. */
    val isBar: Boolean get() = this == REPORTER_ACCOUNT || this == REPORTER_LINK
}

/** One measure (`veyq_moderation_block`): in force while [liftedAt] is null; [activeKey] holds one per target and kind. */
@Entity
@Table(name = "veyq_moderation_block")
@DynamicUpdate
class ModerationBlockEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "kind", nullable = false, length = 24)
    lateinit var kind: String

    @Column(name = "target_id", nullable = false, length = 64)
    lateinit var targetId: String

    /** A guest identity's link, blocked with it (ADR 0093 § 6). */
    @Column(name = "share_id", length = 64)
    var shareId: String? = null

    /** A content block's space — the listings ask per space. */
    @Column(name = "scope_id", columnDefinition = "uuid")
    var scopeId: String? = null

    @Column(name = "case_id", columnDefinition = "uuid")
    var caseId: String? = null

    @Column(name = "taken_by", nullable = false, columnDefinition = "uuid")
    lateinit var takenBy: String

    @Column(name = "taken_at", nullable = false)
    lateinit var takenAt: Instant

    @Column(name = "lifted_at")
    var liftedAt: Instant? = null

    @Column(name = "lifted_by", columnDefinition = "uuid")
    var liftedBy: String? = null

    @Column(name = "active_key", length = 96)
    var activeKey: String? = null

    val blockKind: BlockKind get() = BlockKind.valueOf(kind)

    companion object : PanacheCompanionBase<ModerationBlockEntity, String> {
        fun activeKeyOf(kind: BlockKind, targetId: String) = "${kind.name}:$targetId"
    }
}

/** What a Server Owner's step a moderator proposed is (ADR 0099 § 6). */
enum class ProposalStep { MEMBER_SIGN_IN, TENANT_OFFLINE }

/** What became of a proposal. */
enum class ProposalOutcome { OPEN, TAKEN, DECLINED }

/** One proposal (`veyq_moderation_proposal`); [openKey] holds one open per case, step and target. */
@Entity
@Table(name = "veyq_moderation_proposal")
@DynamicUpdate
class ModerationProposalEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "case_id", nullable = false, columnDefinition = "uuid")
    lateinit var caseId: String

    @Column(name = "step", nullable = false, length = 24)
    lateinit var step: String

    @Column(name = "target_id", nullable = false, columnDefinition = "uuid")
    lateinit var targetId: String

    @Column(name = "proposed_by", nullable = false, columnDefinition = "uuid")
    lateinit var proposedBy: String

    @Column(name = "proposed_at", nullable = false)
    lateinit var proposedAt: Instant

    @Column(name = "outcome", nullable = false, length = 16)
    lateinit var outcome: String

    @Column(name = "settled_at")
    var settledAt: Instant? = null

    @Column(name = "settled_by", columnDefinition = "uuid")
    var settledBy: String? = null

    @Column(name = "open_key", length = 160)
    var openKey: String? = null

    companion object : PanacheCompanionBase<ModerationProposalEntity, String> {
        fun openKeyOf(caseId: String, step: ProposalStep, targetId: String) = "$caseId:${step.name}:$targetId"
    }
}
