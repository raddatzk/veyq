package com.veyq.app.features.tasks

import com.veyq.app.shared.membership.MembershipEntity
import com.veyq.app.features.spaces.scope.ScopeEntity
import com.veyq.app.shared.identity.VeyqUser
import com.veyq.app.shared.http.FailureStatus.CONFLICT
import com.veyq.app.shared.http.FailureStatus.NOT_FOUND
import com.veyq.app.shared.http.Failures
import com.veyq.app.shared.http.HttpFailure
import com.veyq.app.shared.http.httpFailure
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.UUID

/** The resolved delivery target for a grant-delivery job — operational metadata the server already holds. */
data class GrantDeliveryTargetView(
    val targetUserId: String,
    val targetMembershipId: String,
    val identityPublicKey: String,
)

/** A leased job handed back to a worker (a plain view — never a managed entity, so no L1-cache surprises). */
data class ClaimedJobView(
    val jobId: String,
    val scopeId: String,
    val capabilityTag: String,
    val resourceRef: String?,
    val sealedBody: String?,
    val leaseExpiresAt: Instant?,
    val attempts: Int,
    val grantDelivery: GrantDeliveryTargetView?,
)

/** Outcome of a heartbeat: the job was found in the caller's scope but may not be leased to them. */
sealed interface HeartbeatResult {
    data class Extended(val leaseExpiresAt: Instant) : HeartbeatResult
    object NotFound : HeartbeatResult, HttpFailure by httpFailure("job_not_found", NOT_FOUND)
    object NotHolder : HeartbeatResult, HttpFailure by httpFailure("not_lease_holder", CONFLICT)
}

/** Outcome of a completion. */
sealed interface CompleteResult {
    object Done : CompleteResult
    object NotFound : CompleteResult, HttpFailure by httpFailure("job_not_found", NOT_FOUND)
    object MissingResult : CompleteResult, HttpFailure by Failures.invalidRequest
}

/** Outcome of a failure report — the job's status + attempts after requeue-or-dead-letter. */
sealed interface FailResult {
    data class Recorded(val status: String, val attempts: Int) : FailResult
    object NotFound : FailResult, HttpFailure by httpFailure("job_not_found", NOT_FOUND)
}

/** Outcome of a decline — the job released back to pending, or the caller wasn't its lease-holder. */
sealed interface DeclineResult {
    data class Released(val status: String, val attempts: Int) : DeclineResult
    object NotFound : DeclineResult, HttpFailure by httpFailure("job_not_found", NOT_FOUND)
    object NotHolder : DeclineResult, HttpFailure by httpFailure("not_lease_holder", CONFLICT)
}

/**
 * Persistence + the atomic claim/lease machinery for the operator-blind background-task queue (ADR 0021,
 * cr-0076). Workers are remote key-holders that pull over HTTP, so the claim is a portable **compare-and-set**
 * on `status` (works identically on the H2 test DB and MariaDB; MariaDB `SKIP LOCKED` is available as a
 * throughput optimisation but not required for correctness). Operator-blind throughout: the store routes by
 * scope + opaque tag and stores opaque ciphertext; it never holds a scope key.
 */
@ApplicationScoped
class TasksStore {

    /** The scopes the caller can act on as a worker: those where they hold a grant (i.e. hold `K_SC`). */
    fun grantedScopeIds(userId: String): Set<String> =
        MembershipEntity.find("userId = ?1 and wrappedGrant is not null", userId)
            .list().map { it.scopeId }.toSet()

    /**
     * Atomically lease one claimable job for this worker: `scope ∈ granted memberships ∧ tag ∈ [tags]`, where
     * a PENDING job — or a LEASED job whose lease has expired (lazy reclaim) — is claimable. Reconciles
     * grant-delivery jobs first (structural origination) and dead-letters exhausted expired jobs. Returns the
     * leased job, or null when nothing is claimable.
     */
    @Transactional
    fun claim(
        userId: String, tags: List<String>, scopeFilter: String?,
        ttl: java.time.Duration, maxAttempts: Int,
    ): ClaimedJobView? {
        // Eligible scopes = the caller's granted scopes, optionally narrowed to one the worker has opened.
        val scopeIds = grantedScopeIds(userId).let { g -> if (scopeFilter != null) g intersect setOf(scopeFilter) else g }
        if (scopeIds.isEmpty() || tags.isEmpty()) return null

        val now = Instant.now()
        if (CapabilityTags.GRANT_DELIVERY in tags) reconcileGrantDelivery(scopeIds, maxAttempts)
        sweepDeadLetters(scopeIds, tags, now)

        // Claimable candidates (auto-flush picks up the reconciled inserts). Managed entities, but never
        // mutated — we CAS via a bulk update and build the view from known values, so nothing dirties at flush.
        // Exclude jobs this worker has DECLINED (cr-0098): a worker that refused a job (a conflicting identity pin)
        // must never be re-offered it — but it stays claimable by other workers (per-device, not a global veto).
        val candidates = JobEntity.find(
            "scopeId in ?1 and capabilityTag in ?2 and attempts < maxAttempts and " +
                "(status = ?3 or (status = ?4 and leaseExpiresAt < ?5)) and " +
                "id not in (select d.jobId from JobDeclineEntity d where d.workerId = ?6) order by createdAt",
            scopeIds, tags, JobEntity.PENDING, JobEntity.LEASED, now, userId,
        ).list()

        for (c in candidates) {
            val newExpiry = now.plus(ttl)
            val won = if (c.status == JobEntity.PENDING) {
                JobEntity.update(
                    "status = ?1, leaseHolder = ?2, leaseExpiresAt = ?3, attempts = attempts + 1 " +
                        "where id = ?4 and status = ?5",
                    JobEntity.LEASED, userId, newExpiry, c.id, JobEntity.PENDING,
                )
            } else {
                // Reclaim: guard on the exact expiry we read, so only one racing worker re-leases it.
                // (A LEASED candidate always has a lease expiry — the query filtered on leaseExpiresAt < now.)
                JobEntity.update(
                    "status = ?1, leaseHolder = ?2, leaseExpiresAt = ?3, attempts = attempts + 1 " +
                        "where id = ?4 and status = ?5 and leaseExpiresAt = ?6",
                    JobEntity.LEASED, userId, newExpiry, c.id, JobEntity.LEASED, c.leaseExpiresAt!!,
                )
            }
            if (won == 1) {
                val grant = if (c.capabilityTag == CapabilityTags.GRANT_DELIVERY) resolveGrantTarget(c.resourceRef) else null
                return ClaimedJobView(
                    jobId = c.id, scopeId = c.scopeId, capabilityTag = c.capabilityTag,
                    resourceRef = c.resourceRef, sealedBody = c.sealedBody, leaseExpiresAt = newExpiry,
                    attempts = c.attempts + 1, grantDelivery = grant,
                )
            }
        }
        return null
    }

    /** Extend the caller's lease on a job they hold. */
    @Transactional
    fun heartbeat(userId: String, jobId: String, ttl: java.time.Duration): HeartbeatResult {
        val job = JobEntity.findById(jobId) ?: return HeartbeatResult.NotFound
        if (job.scopeId !in grantedScopeIds(userId)) return HeartbeatResult.NotFound
        val newExpiry = Instant.now().plus(ttl)
        val rows = JobEntity.update(
            "leaseExpiresAt = ?1 where id = ?2 and leaseHolder = ?3 and status = ?4",
            newExpiry, jobId, userId, JobEntity.LEASED,
        )
        return if (rows == 1) HeartbeatResult.Extended(newExpiry) else HeartbeatResult.NotHolder
    }

    /**
     * Mark a job done. For grant-delivery the `sealedResult` **is** the wrappedGrant, written onto the target
     * membership — idempotently (no-op if the target already holds a grant, first-online-wins). A job already
     * DONE is a success no-op. The caller must hold a grant in the job's scope (be a key-holder there).
     */
    @Transactional
    fun complete(userId: String, jobId: String, sealedResult: String?): CompleteResult {
        val job = JobEntity.findById(jobId) ?: return CompleteResult.NotFound
        if (job.scopeId !in grantedScopeIds(userId)) return CompleteResult.NotFound
        if (job.status == JobEntity.DONE) return CompleteResult.Done // idempotent no-op

        if (job.capabilityTag == CapabilityTags.GRANT_DELIVERY) {
            val grant = sealedResult ?: return CompleteResult.MissingResult
            // The worker wrapped E_current, so record grantedEpoch = the scope's current epoch — else the client
            // peels the chain from the wrong base (treats E_current as E_0) and can't read post-rotation content
            // (cr-0093). Deliver onto any membership that is BEHIND: grant-less OR stale (grantedEpoch < current —
            // e.g. a rotation re-wrap that failed, §E2). Idempotent + first-online-wins: a no-op once the member is
            // already at the current epoch. The write is bound to the job's OWN scope (`and scopeId = ?4`) — the
            // caller was authorized against `job.scopeId`, so it may only ever write onto a membership *in that
            // scope*; without this an attacker holding a grant in any scope could target a foreign id (cr-0081).
            val epoch = ScopeEntity.findById(job.scopeId)?.currentEpoch ?: 0
            job.resourceRef?.let { membershipId ->
                MembershipEntity.update(
                    "wrappedGrant = ?1, grantedEpoch = ?2 where id = ?3 and scopeId = ?4 " +
                        "and (wrappedGrant is null or grantedEpoch < ?2) and pendingConsent = false",
                    grant, epoch, membershipId, job.scopeId,
                )
            }
        }
        JobEntity.update(
            "status = ?1, leaseHolder = null, leaseExpiresAt = null where id = ?2", JobEntity.DONE, jobId,
        )
        if (sealedResult != null) JobEntity.update("sealedResult = ?1 where id = ?2", sealedResult, jobId)
        return CompleteResult.Done
    }

    /** Record a failed attempt: requeue (PENDING) while attempts remain, else dead-letter (FAILED). */
    @Transactional
    fun fail(userId: String, jobId: String): FailResult {
        val job = JobEntity.findById(jobId) ?: return FailResult.NotFound
        if (job.scopeId !in grantedScopeIds(userId)) return FailResult.NotFound
        // attempts was already incremented at claim; dead-letter once it has reached the job's own max.
        val newStatus = if (job.attempts >= job.maxAttempts) JobEntity.FAILED else JobEntity.PENDING
        val rows = JobEntity.update(
            "status = ?1, leaseHolder = null, leaseExpiresAt = null where id = ?2 and status = ?3",
            newStatus, jobId, JobEntity.LEASED,
        )
        // fail doesn't change attempts, so the count is what we loaded; the status is the one we just set.
        // The bulk update bypasses the persistence context, so we don't re-read the (now-stale) managed entity
        // — on a lost race (another worker already acted) refresh to report the authoritative state instead.
        if (rows == 1) return FailResult.Recorded(newStatus, job.attempts)
        JobEntity.getEntityManager().refresh(job)
        return FailResult.Recorded(job.status, job.attempts)
    }

    /**
     * A worker **declines** a job it holds (cr-0098, ADR 0028): release the lease back to PENDING — claimable by
     * OTHER workers — and record a `(job, worker)` decline so this worker is never re-offered it (the exclusion in
     * [claim]'s candidate query). Unlike [fail], the claim's attempt increment is **rolled back** (`attempts - 1`),
     * so a legitimately-declined job is never dead-lettered by declines alone. Only the lease-holder may decline
     * (`and leaseHolder = ?`), else [DeclineResult.NotHolder] → 409. The job must be in a scope the caller holds a
     * grant in, else [DeclineResult.NotFound] → 404. Per-device, not a global veto: the blind server never
     * adjudicates the crypto claim, it only removes this worker from the candidate set.
     */
    @Transactional
    fun decline(userId: String, jobId: String, reason: String?): DeclineResult {
        val job = JobEntity.findById(jobId) ?: return DeclineResult.NotFound
        if (job.scopeId !in grantedScopeIds(userId)) return DeclineResult.NotFound
        val rows = JobEntity.update(
            "status = ?1, leaseHolder = null, leaseExpiresAt = null, attempts = attempts - 1 " +
                "where id = ?2 and leaseHolder = ?3 and status = ?4",
            JobEntity.PENDING, jobId, userId, JobEntity.LEASED,
        )
        if (rows != 1) return DeclineResult.NotHolder
        // Record the decline once (idempotent — the unique (job, worker) index also guards it). After this the
        // worker can no longer claim the job, so a second decline can't occur in normal operation.
        if (JobDeclineEntity.count("jobId = ?1 and workerId = ?2", jobId, userId) == 0L) {
            JobDeclineEntity().apply {
                id = UUID.randomUUID().toString()
                this.jobId = jobId
                this.workerId = userId
                this.reason = reason
                declinedAt = Instant.now()
            }.persist()
        }
        return DeclineResult.Released(JobEntity.PENDING, job.attempts - 1)
    }

    /** Enqueue a client-originated job onto a scope the caller holds a grant for. Returns the jobId, or null. */
    @Transactional
    fun enqueue(
        userId: String, scopeId: String, capabilityTag: String,
        resourceRef: String?, sealedBody: String?, maxAttempts: Int,
    ): String? {
        if (scopeId !in grantedScopeIds(userId)) return null
        // Structural tags (grant-delivery) are server-originated only — never forgeable via the client front
        // door, so a client can't fabricate a delivery job with an arbitrary target (cr-0081 hardening).
        if (capabilityTag == CapabilityTags.GRANT_DELIVERY) return null
        val jobId = UUID.randomUUID().toString()
        JobEntity().apply {
            id = jobId
            this.scopeId = scopeId
            this.capabilityTag = capabilityTag
            this.resourceRef = resourceRef
            status = JobEntity.PENDING
            attempts = 0
            this.maxAttempts = maxAttempts
            this.sealedBody = sealedBody
            createdAt = Instant.now()
        }.persist()
        return jobId
    }

    // --- scope-rotation event origination (cr-0092) ---------------------------------------------------------

    /**
     * Originate a **scope-rotation** job for a GROUP whose co-shared membership was just cut, when no key-holder
     * was online to rotate synchronously (cr-0092, ADR 0025 — groups rotate). Unlike grant-delivery this can
     * **not** be reconciled from DB state: a removal cut advances no epoch and leaves no stale-epoch mismatch, so
     * the pending-rotation state has no structural trace — it must be recorded **explicitly** here, at the moment
     * of the cut. Idempotent + **coalescing**: skips when a live (PENDING/LEASED) rotation job already exists for
     * the scope, so multiple removals collapse into one rotation that re-grants the whole surviving set. The job
     * carries only the opaque tag + `scopeId` (also mirrored into `resourceRef`) — never a key. Returns whether a
     * job was created.
     */
    @Transactional
    fun originateScopeRotation(scopeId: String, maxAttempts: Int): Boolean {
        val live = JobEntity.count(
            "scopeId = ?1 and capabilityTag = ?2 and status in ?3",
            scopeId, CapabilityTags.SCOPE_ROTATION, listOf(JobEntity.PENDING, JobEntity.LEASED),
        )
        if (live > 0) return false
        JobEntity().apply {
            id = UUID.randomUUID().toString()
            this.scopeId = scopeId
            capabilityTag = CapabilityTags.SCOPE_ROTATION
            resourceRef = scopeId
            status = JobEntity.PENDING
            attempts = 0
            this.maxAttempts = maxAttempts
            createdAt = Instant.now()
        }.persist()
        return true
    }

    // --- grant-delivery structural origination + dead-letter sweep -------------------------------------------

    /**
     * Ensure every **behind** membership in these scopes has a PENDING grant-delivery job (server-structural
     * origination — the server already understands "a role without the current key means deliver it"). A member is
     * behind when they are grant-less (never delivered) OR **stale** (`grantedEpoch < the scope's currentEpoch` —
     * e.g. a rotation re-wrap failed for them, §E2; cr-0093). Idempotent: skips memberships that already have a live
     * (PENDING/LEASED) grant-delivery job. Runs on the pull path (reconcile-on-claim) and from a scheduled backstop.
     */
    @Transactional
    fun reconcileGrantDelivery(scopeIds: Set<String>, maxAttempts: Int): Int {
        if (scopeIds.isEmpty()) return 0
        val epochByScope = ScopeEntity.find("id in ?1", scopeIds).list().associate { it.id to it.currentEpoch }
        // A soft `pendingConsent` proposal (cr-0094) is grant-less but NEVER a delivery target: the consent gate is
        // "no `K_SC` wrap before the invitee accepts", so it is skipped until Accept clears the flag.
        val pending = MembershipEntity.find("scopeId in ?1", scopeIds).list()
            .filter { !it.pendingConsent && (it.wrappedGrant == null || it.grantedEpoch < (epochByScope[it.scopeId] ?: 0)) }
        var created = 0
        for (m in pending) {
            val live = JobEntity.count(
                "resourceRef = ?1 and capabilityTag = ?2 and status in ?3",
                m.id, CapabilityTags.GRANT_DELIVERY, listOf(JobEntity.PENDING, JobEntity.LEASED),
            )
            if (live > 0) continue
            JobEntity().apply {
                id = UUID.randomUUID().toString()
                scopeId = m.scopeId
                capabilityTag = CapabilityTags.GRANT_DELIVERY
                resourceRef = m.id
                status = JobEntity.PENDING
                attempts = 0
                this.maxAttempts = maxAttempts
                createdAt = Instant.now()
            }.persist()
            created++
        }
        return created
    }

    /** The scheduled backstop: reconcile grant-delivery across every scope that has a **behind** membership — one
     *  that is grant-less, or stale in a rotated scope (`grantedEpoch < currentEpoch`; cr-0093). `reconcileGrantDelivery`
     *  then filters each scope down to its actually-behind members. */
    @Transactional
    fun reconcileAllGrantDelivery(maxAttempts: Int): Int {
        val grantless = MembershipEntity.find("wrappedGrant is null").list().map { it.scopeId }.toSet()
        val rotated = ScopeEntity.find("currentEpoch > 0").list().map { it.id }.toSet()
        return reconcileGrantDelivery(grantless + rotated, maxAttempts)
    }

    /** Lazily dead-letter expired-lease jobs that have exhausted their attempts (a crashed poison worker). */
    private fun sweepDeadLetters(scopeIds: Set<String>, tags: List<String>, now: Instant) {
        JobEntity.update(
            "status = ?1, leaseHolder = null, leaseExpiresAt = null where scopeId in ?2 and capabilityTag in ?3 " +
                "and status = ?4 and leaseExpiresAt < ?5 and attempts >= maxAttempts",
            JobEntity.FAILED, scopeIds, tags, JobEntity.LEASED, now,
        )
    }

    /** Resolve a grant-delivery job's target: the pending member's userId + identity public key. */
    private fun resolveGrantTarget(membershipId: String?): GrantDeliveryTargetView? {
        val m = membershipId?.let { MembershipEntity.findById(it) } ?: return null
        val user = VeyqUser.findById(m.userId) ?: return null
        return GrantDeliveryTargetView(m.userId, m.id, user.identityPublicKey)
    }
}
