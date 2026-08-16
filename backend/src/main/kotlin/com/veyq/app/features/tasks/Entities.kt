package com.veyq.app.features.tasks

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * A background job — the operator-blind, scope-bound envelope (ADR 0021, cr-0076). The server sees only the
 * cleartext routing + lease bookkeeping; `sealedBody` / `sealedResult` are opaque ciphertext it stores but
 * never unseals, and `capabilityTag` is an opaque token, not a plaintext label. Workers are remote key-holders
 * that pull over HTTP; the claim is a portable compare-and-set on `status` (no server-side execution).
 *
 * status: PENDING (claimable) → LEASED (held until [leaseExpiresAt]) → DONE, or → FAILED (dead-lettered once
 * [attempts] reaches [maxAttempts]). A LEASED job past its lease is lazily reclaimable (crash resilience).
 */
@Entity
@Table(name = "veyq_job")
class JobEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", length = 36)
    lateinit var id: String

    /** Routing key — the server already knows membership, so it matches claims against the caller's scopes. */
    @Column(name = "scope_id", nullable = false, length = 36)
    lateinit var scopeId: String

    /** Opaque capability token from the registry ([CapabilityTags]); matched against a worker's claimed tags. */
    @Column(name = "capability_tag", nullable = false, length = 64)
    lateinit var capabilityTag: String

    /** Opaque id of the resource acted on (for grant-delivery, the target membership id). */
    @Column(name = "resource_ref", length = 64)
    var resourceRef: String? = null

    @Column(name = "status", nullable = false, length = 16)
    lateinit var status: String

    /** The worker's opaque lease id while LEASED; null when PENDING/terminal. */
    @Column(name = "lease_holder", length = 36)
    var leaseHolder: String? = null

    @Column(name = "lease_expires_at")
    var leaseExpiresAt: Instant? = null

    @Column(name = "attempts", nullable = false)
    var attempts: Int = 0

    @Column(name = "max_attempts", nullable = false)
    var maxAttempts: Int = 0

    /** Sealed task body (`type` + params) under a K_SC-derived key, context `job`; null for structural jobs. */
    @Column(name = "sealed_body", columnDefinition = "MEDIUMTEXT")
    var sealedBody: String? = null

    /** An optional sealed job result (for grant-delivery, the wrappedGrant written onto the target membership). */
    @Column(name = "sealed_result", columnDefinition = "MEDIUMTEXT")
    var sealedResult: String? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    companion object : PanacheCompanionBase<JobEntity, String> {
        const val PENDING = "PENDING"
        const val LEASED = "LEASED"
        const val DONE = "DONE"
        const val FAILED = "FAILED"
    }
}

/**
 * A per-worker decline of a job (cr-0098, ADR 0028 §Consequences). Recorded when a grant-delivery worker refuses
 * a job because it holds a DIFFERENT pin for the target's identity key: it releases the claim back to PENDING (the
 * job stays claimable by OTHER workers) and writes this row so the claim/reconcile machinery never re-offers THIS
 * job to THIS worker again. `workerId` is the **user** id, so the exclusion is per key-holder (the decliner's
 * other devices are excluded too) and not a global veto — the blind server never adjudicates the crypto claim.
 * ADR 0060 Decision 4 rests on that granularity: a rotation exclusion is a row here for the rotator.
 * Operational metadata only (no key, no trust graph); surrogate id + a unique (job, worker) index.
 */
@Entity
@Table(name = "veyq_job_decline")
class JobDeclineEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", length = 36)
    lateinit var id: String

    @Column(name = "job_id", nullable = false, length = 36)
    lateinit var jobId: String

    /** The declining worker's opaque id (the `SecurityIdentity` principal / lease holder). */
    @Column(name = "worker_id", nullable = false, length = 36)
    lateinit var workerId: String

    @Column(name = "reason", length = 256)
    var reason: String? = null

    @Column(name = "declined_at", nullable = false)
    lateinit var declinedAt: Instant

    companion object : PanacheCompanionBase<JobDeclineEntity, String>
}
