package me.raddatz.veyq.core.moderation.hold

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicUpdate
import java.time.Instant

/**
 * Why a hold ends when it does (ADR 0093 § 4's table): taken with the case ([CASE_OPEN], 90 days), moved by a decision
 * ([MEASURE], six months after its statement), set by a logged extension ([EXTENDED]), or by an authority's order
 * ([ORDER], which also sets the hold's floor).
 */
enum class HoldTrigger { CASE_OPEN, MEASURE, EXTENDED, ORDER }

/** A hold (`veyq_hold`, en.moderation.hold): its trigger and its end. */
@Entity
@Table(name = "veyq_hold")
@DynamicUpdate
class HoldEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "case_id", columnDefinition = "uuid")
    var caseId: String? = null

    @Column(name = "hold_trigger", nullable = false, length = 24)
    lateinit var trigger: String

    @Column(name = "expires_at", nullable = false)
    lateinit var expiresAt: Instant

    /** The end an authority's order sets: no extension, lift or decision ends the hold before it; null without an order. */
    @Column(name = "floor_until")
    var floorUntil: Instant? = null

    /**
     * The latest end a measure or a statement may move the hold to: a child-sexual-abuse case's 90 days (ADR 0093 § 4,
     * § 184b(5) StGB); null on every other hold. A logged extension may pass it; [floorUntil] always wins.
     */
    @Column(name = "ceiling_until")
    var ceilingUntil: Instant? = null

    companion object : PanacheCompanionBase<HoldEntity, String>
}

/** One copied row (`veyq_hold_row`): its source and its columns as JSON, ciphertext and metadata as stored. */
@Entity
@Table(name = "veyq_hold_row")
@DynamicUpdate
class HoldRowEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "hold_id", nullable = false, columnDefinition = "uuid")
    lateinit var holdId: String

    @Column(name = "source_table", nullable = false, length = 64)
    lateinit var sourceTable: String

    @Column(name = "source_id", nullable = false, length = 64)
    lateinit var sourceId: String

    @Column(name = "payload", nullable = false, columnDefinition = "MEDIUMTEXT")
    lateinit var payload: String

    companion object : PanacheCompanionBase<HoldRowEntity, String>
}

/** A pinned store object prefix (`veyq_hold_pin`): the reclaim sweep skips its key until [expiresAt]. */
@Entity
@Table(name = "veyq_hold_pin")
@DynamicUpdate
class HoldPinEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "hold_id", nullable = false, columnDefinition = "uuid")
    lateinit var holdId: String

    @Column(name = "storage_key", nullable = false, length = 64)
    lateinit var storageKey: String

    @Column(name = "chunk_count", nullable = false)
    var chunkCount: Int = 0

    @Column(name = "expires_at", nullable = false)
    lateinit var expiresAt: Instant

    companion object : PanacheCompanionBase<HoldPinEntity, String>
}
