package me.raddatz.veyq.core.admin.tenant

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.panache.common.Sort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import me.raddatz.veyq.core.crypto.SealedValue
import me.raddatz.veyq.core.scope.MembershipEraser
import org.hibernate.annotations.DynamicUpdate
import java.io.Serializable
import java.time.Instant

/**
 * One member's signed verification of another member of a tenant (ADR 0111 decisions 12-13): *"in this tenant, this
 * verifier at this head of their key chain confirmed this subject at this head of theirs"*, sealed at [epoch] under a
 * value key of its own (field `member-verification`), which [wrappedValueKey] holds under the tenant key (ADR 0115): a
 * rotation re-wraps it and never rewrites [sealedStatement], so no rotator can rewrite another member's verification.
 * The server stores and relays both verbatim and never opens them. One row per (tenant, verifier, subject); the
 * verifier's next put replaces it. Every member of the tenant reads them.
 *
 * Gone with either account and with the tenant (`ON DELETE CASCADE`), and with a member's removal from the tenant
 * ([VerificationMembershipEraser]).
 */
@Entity
@Table(name = "veyq_verification")
@IdClass(VerificationKey::class)
@DynamicUpdate
class VerificationEntity : PanacheEntityBase {
    @Id
    @Column(name = "tenant_id", columnDefinition = "uuid")
    lateinit var tenantId: String

    @Id
    @Column(name = "verifier_user_id", columnDefinition = "uuid")
    lateinit var verifierUserId: String

    @Id
    @Column(name = "subject_user_id", columnDefinition = "uuid")
    lateinit var subjectUserId: String

    @Column(name = "epoch", nullable = false)
    var epoch: Int = 0

    @Column(name = "sealed_statement", nullable = false, columnDefinition = "TEXT")
    lateinit var sealedStatement: String

    /** [sealedStatement]'s value key under the tenant key of its current epoch (`value-key`). */
    @Column(name = "wrapped_value_key", nullable = false, length = 128)
    lateinit var wrappedValueKey: String

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    companion object : PanacheCompanionBase<VerificationEntity, VerificationKey>
}

/** The verification's composite key. Defaults give JPA its no-argument constructor. */
data class VerificationKey(
    var tenantId: String = "",
    var verifierUserId: String = "",
    var subjectUserId: String = "",
) : Serializable

/** One page of a tenant's verifications, and whether rows follow it. */
data class VerificationPageView(val rows: List<VerificationEntity>, val more: Boolean)

/**
 * Reads and writes of the tenant's verifications. Decides nothing about who may: [TenantVerificationHandler] gates every
 * call, and writes only under the tenant scope's row lock.
 */
@ApplicationScoped
class VerificationStore {

    /**
     * Store [verifierUserId]'s verification of [subjectUserId], replacing the one it held. The caller holds the tenant
     * scope's row lock, so two puts of one pair never race to the insert.
     */
    fun put(tenantId: String, verifierUserId: String, subjectUserId: String, epoch: Int, statement: SealedValue, now: Instant) {
        val existing = VerificationEntity.findById(VerificationKey(tenantId, verifierUserId, subjectUserId))
        if (existing != null) {
            existing.epoch = epoch
            existing.sealedStatement = statement.value
            existing.wrappedValueKey = statement.wrappedValueKey
            existing.createdAt = now
            return
        }
        VerificationEntity().apply {
            this.tenantId = tenantId
            this.verifierUserId = verifierUserId
            this.subjectUserId = subjectUserId
            this.epoch = epoch
            sealedStatement = statement.value
            wrappedValueKey = statement.wrappedValueKey
            this.createdAt = now
        }.persist()
    }

    /**
     * Up to [limit] rows of the tenant, optionally of one subject and/or one verifier, ordered by verifier then subject
     * and strictly after the pair [after] — keyset paging, so a page is the same whatever was written before it. One row
     * more than asked says whether another page follows. The order is the id columns' own (a `UUID` column's), the same
     * one the `>` of the cursor compares by, so the pages tile the set whatever that order is.
     */
    fun page(tenantId: String, subjectUserId: String?, verifierUserId: String?, after: Pair<String, String>?, limit: Int): VerificationPageView {
        val clauses = mutableListOf("tenantId = :tenant")
        val params = mutableMapOf<String, Any>("tenant" to tenantId)
        if (subjectUserId != null) { clauses += "subjectUserId = :subject"; params["subject"] = subjectUserId }
        if (verifierUserId != null) { clauses += "verifierUserId = :verifier"; params["verifier"] = verifierUserId }
        if (after != null) {
            clauses += "(verifierUserId > :afterVerifier or (verifierUserId = :afterVerifier and subjectUserId > :afterSubject))"
            params["afterVerifier"] = after.first
            params["afterSubject"] = after.second
        }
        val rows = VerificationEntity.find(clauses.joinToString(" and "), Sort.by("verifierUserId").and("subjectUserId"), params)
            .range(0, limit).list()
        return VerificationPageView(rows.take(limit), rows.size > limit)
    }
}

/**
 * A member who leaves the tenant's TENANT scope takes every verification of that tenant that names them, as verifier
 * and as subject: a verification counts only between members (ADR 0111 decision 12), and the rows of a former member
 * would otherwise be read by everyone who stays. Rows are keyed by the tenant's id, which is its root scope's id, so on
 * any other scope this deletes nothing. Runs in the removal's transaction, under the scope lock it holds — the lock
 * [TenantVerificationHandler.put] takes too, so a put racing a removal either lands first and is deleted here, or
 * finds the subject or the caller gone.
 */
@ApplicationScoped
class VerificationMembershipEraser : MembershipEraser {
    override fun eraseMembership(userId: String, scopeId: String) {
        VerificationEntity.delete("tenantId = ?1 and (verifierUserId = ?2 or subjectUserId = ?2)", scopeId, userId)
    }
}
