package me.raddatz.veyq.core.moderation.order

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicUpdate
import java.time.Instant

/**
 * What an authority's order asks (ADR 0093 § 12): [REMOVAL] blocks what it names at once (TCO Art. 3); [PRESERVATION]
 * (Regulation 2023/1543) and [INFORMATION] (§§ 100j-100k StPO) keep it, with no block.
 */
enum class OrderKind { REMOVAL, PRESERVATION, INFORMATION }

/** One order as recorded (`veyq_authority_order`); see the migration for each column. */
@Entity
@Table(name = "veyq_authority_order")
@DynamicUpdate
class AuthorityOrderEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "kind", nullable = false, length = 16)
    lateinit var kind: String

    @Column(name = "share_id", nullable = false, length = 64)
    lateinit var shareId: String

    @Column(name = "received_at", nullable = false)
    lateinit var receivedAt: Instant

    @Column(name = "entered_at", nullable = false)
    lateinit var enteredAt: Instant

    @Column(name = "entered_by", nullable = false, columnDefinition = "uuid")
    lateinit var enteredBy: String

    @Column(name = "epoch", nullable = false)
    var epoch: Int = 0

    @Column(name = "sealed_reference", nullable = false, columnDefinition = "TEXT")
    lateinit var sealedReference: String

    /** The case it opened; null when its share was not found. */
    @Column(name = "case_id", columnDefinition = "uuid")
    var caseId: String? = null

    /** When the share and its content were both blocked (a removal order that found its share). */
    @Column(name = "blocked_at")
    var blockedAt: Instant? = null

    /** The end the order sets for its hold — the hold's floor. */
    @Column(name = "hold_until")
    var holdUntil: Instant? = null

    val orderKind: OrderKind get() = OrderKind.valueOf(kind)

    companion object : PanacheCompanionBase<AuthorityOrderEntity, String>
}
