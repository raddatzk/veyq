package com.veyq.app.features.tasks

import com.veyq.app.shared.membership.Admission
import com.veyq.app.shared.membership.MembershipEntity
import com.veyq.app.shared.membership.admission
import com.veyq.app.shared.scope.ScopeEntity
import com.veyq.app.shared.identity.VeyqUser
import com.veyq.app.shared.http.FailureStatus.CONFLICT
import com.veyq.app.shared.http.FailureStatus.NOT_FOUND
import com.veyq.app.shared.http.Failures
import com.veyq.app.shared.http.HttpFailure
import com.veyq.app.shared.http.httpFailure
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.UUID

/** The resolved delivery target for a grant-delivery job — operational metadata the server already holds, and the
 *  membership's admission (ADR 0020 move 6), which the worker verifies under its own epoch key before it wraps. */
data class GrantDeliveryTargetView(
    val targetUserId: String,
    val targetMembershipId: String,
    val identityPublicKey: String,
    val admission: Admission?,
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

/** Outcome of a heartbeat: the gate let the caller in, but the job may not be leased to them. */
sealed interface HeartbeatResult {
    data class Extended(val leaseExpiresAt: Instant) : HeartbeatResult
    object Suspended : HeartbeatResult, HttpFailure by Failures.tenantSuspended
    object Deleting : HeartbeatResult, HttpFailure by Failures.scopeDeleting
    object NotFound : HeartbeatResult, HttpFailure by httpFailure("job_not_found", NOT_FOUND)
    object NotHolder : HeartbeatResult, HttpFailure by httpFailure("not_lease_holder", CONFLICT)
}

/** Outcome of a completion. */
sealed interface CompleteResult {
    object Done : CompleteResult
    object Suspended : CompleteResult, HttpFailure by Failures.tenantSuspended
    object Deleting : CompleteResult, HttpFailure by Failures.scopeDeleting
    object NotFound : CompleteResult, HttpFailure by httpFailure("job_not_found", NOT_FOUND)
    object MissingResult : CompleteResult, HttpFailure by Failures.invalidRequest
    /** The job is not leased to the caller (ADR 0021 §6) — idempotency makes a repeated completion harmless, it does
     *  not make an unclaimed one legitimate. */
    object NotHolder : CompleteResult, HttpFailure by httpFailure("not_lease_holder", CONFLICT)
    /** A grant-delivery completion stated an epoch that is not the scope's current one (ADR 0072): nothing written,
     *  the lease released without spending an attempt, so the job re-runs at the current epoch. */
    object StaleEpoch : CompleteResult, HttpFailure by httpFailure("stale_epoch", CONFLICT)
    /** A `scope-rotation` job whose holder committed no epoch of its scope while holding the lease (#207): nothing
     *  written, the lease kept. The worker's failure report then spends an attempt — the right outcome for a worker
     *  that did not rotate. */
    object RotationNotCommitted : CompleteResult, HttpFailure by httpFailure("rotation_not_committed", CONFLICT)
}

/** One rotator's queued compromise rotation on one scope, and where it stands ([CompromiseRotationPolicy]). */
data class CompromiseRotationRow(val scopeId: String, val rotatorId: String, val state: CompromiseRotationState)

/** Outcome of queueing a dead-lettered compromise rotation again. */
sealed interface RequeueResult {
    /** Queued again; the scope's rows after it. */
    data class Requeued(val rows: List<CompromiseRotationRow>) : RequeueResult
    /** No account rotation (of the named rotator, where one is named) ever queued a rotation on the scope. */
    object NoRotation : RequeueResult
    /** There is one, but none of it is `FAILED` — nothing to queue again. */
    object NotFailed : RequeueResult
}

/** Outcome of a failure report — the job's status + attempts after requeue-or-dead-letter. */
sealed interface FailResult {
    data class Recorded(val status: String, val attempts: Int) : FailResult
    object Suspended : FailResult, HttpFailure by Failures.tenantSuspended
    object Deleting : FailResult, HttpFailure by Failures.scopeDeleting
    object NotFound : FailResult, HttpFailure by httpFailure("job_not_found", NOT_FOUND)
    /** The job is not leased to the caller, or no longer leased at all (ADR 0021 §6). */
    object NotHolder : FailResult, HttpFailure by httpFailure("not_lease_holder", CONFLICT)
}

/** Outcome of a decline — the job released back to pending, or the caller wasn't its lease-holder. */
sealed interface DeclineResult {
    data class Released(val status: String, val attempts: Int) : DeclineResult
    object Suspended : DeclineResult, HttpFailure by Failures.tenantSuspended
    object Deleting : DeclineResult, HttpFailure by Failures.scopeDeleting
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

    /** The scopes the caller owns — `scope-rotation`'s narrowing of eligibility (ADR 0021 §4). */
    fun ownedScopeIds(userId: String): Set<String> =
        MembershipEntity.find("userId = ?1 and role = ?2", userId, MembershipEntity.OWNER)
            .list().map { it.scopeId }.toSet()

    /** A job by id, or null — the handler resolves the job's scope from it before asking the gate. */
    fun find(jobId: String): JobEntity? = JobEntity.findById(jobId)

    /**
     * Atomically lease one claimable job for this worker: `tag ∈ [eligibleByTag] ∧ scope ∈ eligibleByTag[tag]`, where
     * a PENDING job — or a LEASED job whose lease has expired (lazy reclaim) — is claimable. Eligibility is **per
     * tag** (ADR 0021 §4): a request naming several tags still gets each tag's job only on that tag's own scopes, so a
     * `scope-rotation` job never reaches a caller who does not own its scope. Reconciles grant-delivery jobs first
     * (structural origination) and dead-letters exhausted expired jobs. Returns the leased job, or null when nothing
     * is claimable.
     */
    @Transactional
    fun claim(
        userId: String, eligibleByTag: Map<String, Set<String>>,
        ttl: java.time.Duration, maxAttempts: Int,
    ): ClaimedJobView? {
        // Eligible scopes come from the handler, per tag: the caller's granted scopes the scope-access gate lets them
        // write (uc.spaces.scope-access A5), narrowed by what the tag needs, optionally to one the worker has opened.
        val eligible = eligibleByTag.filterValues { it.isNotEmpty() }
        if (eligible.isEmpty()) return null

        val now = Instant.now()
        eligible[CapabilityTags.GRANT_DELIVERY]?.let { reconcileGrantDelivery(it, maxAttempts) }
        eligible.forEach { (tag, scopeIds) -> sweepDeadLetters(scopeIds, tag, now) }

        // Claimable candidates (auto-flush picks up the reconciled inserts), one query per tag over that tag's own
        // scopes, then the oldest first across all of them. Managed entities, but never mutated — we CAS via a bulk
        // update and build the view from known values, so nothing dirties at flush.
        // Exclude jobs this worker has DECLINED (cr-0098): a worker that refused a job (a conflicting identity pin)
        // must never be re-offered it — but it stays claimable by other workers (not a global veto).
        //
        // `?6` is the **userId**, so the exclusion is per KEY-HOLDER, not per device: a second browser of the
        // same account is excluded too. That is the correct reading of a decline (the refusal was a decision
        // about an identity, not about a browser), and ADR 0060 Decision 4 now rests on it — the rotation
        // exclusion is a decline row for the rotator, and a per-device exclusion would let them claim their own
        // rotation from any other tab.
        val candidates = eligible.flatMap { (tag, scopeIds) ->
            JobEntity.find(
                "scopeId in ?1 and capabilityTag = ?2 and attempts < maxAttempts and " +
                    "(status = ?3 or (status = ?4 and leaseExpiresAt < ?5)) and " +
                    "id not in (select d.jobId from JobDeclineEntity d where d.workerId = ?6)",
                scopeIds, tag, JobEntity.PENDING, JobEntity.LEASED, now, userId,
            ).list()
        }.sortedBy { it.createdAt }

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
        JobEntity.findById(jobId) ?: return HeartbeatResult.NotFound
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
     * DONE is a success no-op; otherwise the caller must hold the job's lease (ADR 0021 §6) — which also makes them a
     * key-holder there, since only a granted scope is claimable — and a `scope-rotation` job must carry that holder's
     * own commit, made during its lease ([JobEntity.rotatedEpoch], #207).
     *
     * **The decision is the guarded write, not the read before it** (#235, developer-styleguide §12). The `DONE`
     * lands in one compare-and-set — `status = LEASED and leaseHolder = <caller>`, and for a rotation the commit
     * mark — whose row count decides the answer; the unlocked read above it only picks the answer for a call that
     * cannot win anyway. Without the guard, a holder whose lease expired completes while another worker reclaims:
     * its read still names it, the reclaim commits, and its unconditioned write then erases the new holder's lease.
     * A lost completion writes nothing — the grant-delivery membership write runs only after the guarded `DONE`
     * won. "Holder" keeps meaning `LEASED` to the caller with no expiry test: a holder whose lease expired but was
     * not reclaimed still completes. The scope row is locked first (the stale-epoch read takes it), the order every
     * writer of one scope's jobs and memberships follows, so this cannot deadlock against a rotation.
     */
    @Transactional
    fun complete(userId: String, jobId: String, sealedResult: String?, statedEpoch: Int?, grantSignature: String?): CompleteResult {
        val job = JobEntity.findById(jobId) ?: return CompleteResult.NotFound
        if (job.status == JobEntity.DONE) return CompleteResult.Done // idempotent no-op
        if (job.status != JobEntity.LEASED || job.leaseHolder != userId) return CompleteResult.NotHolder

        if (job.capabilityTag != CapabilityTags.GRANT_DELIVERY) {
            return if (markDone(job, userId, sealedResult) == 1) CompleteResult.Done else lostCompletion(job, userId)
        }
        val grant = sealedResult ?: return CompleteResult.MissingResult
        val epoch = statedEpoch ?: return CompleteResult.MissingResult
        // The worker's signature over the grant it posts (ADR 0028 Decision 6) — stored, never verified here.
        val signature = grantSignature ?: return CompleteResult.MissingResult
        // The grant is sealed bound to (scope, the epoch the worker states) (ADR 0072), so that is the epoch the
        // membership records — and it must be the scope's CURRENT one: a rotation that landed between claim and
        // completion, or a worker still on an older grant, sealed a key the member cannot write with. Refused and
        // released (the claim's attempt rolled back, like a decline) so the job re-runs at the current epoch.
        // The scope row is read under the lock a rotation takes (ScopesStore.rotate), so the two serialize.
        val current = ScopeEntity.lockRow(job.scopeId)?.currentEpoch ?: 0
        if (epoch != current) {
            JobEntity.update(
                "status = ?1, leaseHolder = null, leaseExpiresAt = null, attempts = attempts - 1 " +
                    "where id = ?2 and leaseHolder = ?3 and status = ?4",
                JobEntity.PENDING, jobId, userId, JobEntity.LEASED,
            )
            return CompleteResult.StaleEpoch
        }
        if (markDone(job, userId, sealedResult) != 1) return lostCompletion(job, userId)
        // Deliver onto any membership that is BEHIND: grant-less OR stale (grantedEpoch < current — e.g. a
        // rotation re-wrap that failed, §E2). Idempotent + first-online-wins: a no-op once the member is already
        // at the current epoch. The write is bound to the job's OWN scope (`and scopeId = ?4`) — the caller was
        // authorized against `job.scopeId`, so it may only ever write onto a membership *in that scope*; without
        // this an attacker holding a grant in any scope could target a foreign id (cr-0081).
        // The worker's identity key as of this write is recorded beside its signature: the reads serve it, so the
        // grant stays verifiable after the worker's own account rotation (ADR 0028 § What is still open). The
        // worker is the authenticated lease holder, so its account row exists.
        val workerKey = VeyqUser.findById(userId)!!.identityPublicKey
        job.resourceRef?.let { membershipId ->
            MembershipEntity.update(
                "wrappedGrant = ?1, grantedEpoch = ?2, granterUserId = ?5, grantSignature = ?6, " +
                    "granterIdentityPublicKey = ?7 " +
                    "where id = ?3 and scopeId = ?4 and (wrappedGrant is null or grantedEpoch < ?2) and pendingConsent = false",
                grant, epoch, membershipId, job.scopeId, userId, signature, workerKey,
            )
        }
        return CompleteResult.Done
    }

    /** The guarded `DONE`: only while the job is still leased to [userId], and for a rotation only once that holder
     *  committed during its lease. Returns the rows it wrote — 1 won, 0 lost. */
    private fun markDone(job: JobEntity, userId: String, sealedResult: String?): Int {
        val committed = if (job.capabilityTag == CapabilityTags.SCOPE_ROTATION) " and rotatedEpoch is not null" else ""
        val guard = "where id = ?2 and status = ?3 and leaseHolder = ?4$committed"
        return if (sealedResult == null) {
            JobEntity.update("status = ?1, leaseHolder = null, leaseExpiresAt = null $guard", JobEntity.DONE, job.id, JobEntity.LEASED, userId)
        } else {
            JobEntity.update(
                "status = ?1, leaseHolder = null, leaseExpiresAt = null, sealedResult = ?5 $guard",
                JobEntity.DONE, job.id, JobEntity.LEASED, userId, sealedResult,
            )
        }
    }

    /** The answer for a completion whose guarded write matched no row, read from the job as it is now. */
    private fun lostCompletion(job: JobEntity, userId: String): CompleteResult {
        JobEntity.getEntityManager().refresh(job)
        return when {
            job.status == JobEntity.DONE -> CompleteResult.Done
            job.capabilityTag == CapabilityTags.SCOPE_ROTATION && job.status == JobEntity.LEASED &&
                job.leaseHolder == userId && job.rotatedEpoch == null -> CompleteResult.RotationNotCommitted
            else -> CompleteResult.NotHolder
        }
    }

    /**
     * Record a failed attempt: requeue (PENDING) while attempts remain, else dead-letter (FAILED). Only the lease
     * holder, and only while the job is still LEASED (ADR 0021 §6) — else [FailResult.NotHolder].
     */
    @Transactional
    fun fail(userId: String, jobId: String): FailResult {
        val job = JobEntity.findById(jobId) ?: return FailResult.NotFound
        // attempts was already incremented at claim; dead-letter once it has reached the job's own max.
        val newStatus = if (job.attempts >= job.maxAttempts) JobEntity.FAILED else JobEntity.PENDING
        val rows = JobEntity.update(
            "status = ?1, leaseHolder = null, leaseExpiresAt = null where id = ?2 and status = ?3 and leaseHolder = ?4",
            newStatus, jobId, JobEntity.LEASED, userId,
        )
        // fail doesn't change attempts, so the count is what we loaded; the status is the one we just set. A lost
        // race (the lease expired and another worker re-leased it) matches no row, so it is the same NotHolder.
        return if (rows == 1) FailResult.Recorded(newStatus, job.attempts) else FailResult.NotHolder
    }

    /**
     * A worker **declines** a job it holds (cr-0098, ADR 0028): release the lease back to PENDING — claimable by
     * OTHER workers — and record a `(job, worker)` decline so this worker is never re-offered it (the exclusion in
     * [claim]'s candidate query). Unlike [fail], the claim's attempt increment is **rolled back** (`attempts - 1`),
     * so a legitimately-declined job is never dead-lettered by declines alone. Only the lease-holder may decline
     * (`and leaseHolder = ?`), else [DeclineResult.NotHolder] → 409 — the handler's scope-access gate has already
     * answered a caller outside the job's space. Per **key-holder**, not a global veto: `workerId` is the
     * user id, so the decliner's other devices are excluded too — the refusal was a decision about an identity,
     * not about a browser — while a different key-holder still delivers. The blind server never adjudicates the
     * crypto claim, it only removes this worker from the candidate set.
     */
    @Transactional
    fun decline(userId: String, jobId: String, reason: String?): DeclineResult {
        val job = JobEntity.findById(jobId) ?: return DeclineResult.NotFound
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
        // Server-originated tags are never forgeable via the client front door: a client can't fabricate a delivery
        // job with an arbitrary target (cr-0081), nor a rotation no event asked for and that carries none of an
        // event's exclusions (ADR 0021 §8).
        if (capabilityTag in CapabilityTags.SERVER_ORIGINATED) return null
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
     * Originate a **scope-rotation** job for a GROUP whose co-shared membership was just cut, when the remover could
     * not rotate synchronously (cr-0092, ADR 0025 — groups rotate). Unlike grant-delivery this can **not** be
     * reconciled from DB state: a removal cut advances no epoch and leaves no stale-epoch mismatch, so the
     * pending-rotation state has no structural trace — it must be recorded **explicitly** here, at the moment of the
     * cut. It **joins** the scope's unclaimed (`PENDING`) job if there is one, so removals before anyone is online
     * still cost one rotation; otherwise it creates one ([pendingRotationJob] says why a leased job is never joined).
     * The job carries only the opaque tag + `scopeId` (also mirrored into `resourceRef`) — never a key. Returns
     * whether a job was created.
     *
     * Serialised on the scope row (lock order scope → membership → job, the order `ScopesStore.rotate` follows; a
     * cut takes it before it deletes): two events on one scope cannot both see "no pending job" and create one each.
     */
    @Transactional
    fun originateScopeRotation(scopeId: String, maxAttempts: Int): Boolean {
        ScopeEntity.lockRow(scopeId)
        if (pendingRotationJob(scopeId) != null) return false
        createRotationJob(scopeId, maxAttempts)
        return true
    }

    /**
     * Originate a scope rotation the originator must **not** complete — key rotation after a compromise
     * (ADR 0060 Decision 4, uc.account.rotate-keys §7) — and queue a dead-lettered one again
     * ([requeueFailedRotations]).
     *
     * The exclusion rides on the **existing** decline mechanism rather than on a new column: a
     * `(job, worker)` row is already exactly "this worker may not claim this job", and it is already what
     * [claim]'s candidate query filters on. Written at origination instead of after a refusal, which is the
     * only thing new here.
     *
     * **The decline is written on every live job of the scope**, `PENDING` and `LEASED`, whether or not this call
     * created one. A scope that already carries an unclaimed rotation (a removal cut moments earlier) must exclude
     * the rotator from *that* job, or the rotation this account triggered is completable by the very account it
     * locks out; and a leased job whose lease expires would otherwise be reclaimable by the rotator, who may be an
     * owner. The exclusion does not revoke a lease the rotator already holds — that lease is inert, because
     * `rotateScope` refuses their commit and the completion needs one. The joined or fresh `PENDING` job is what
     * guarantees a rotation whose directory read came after the identity changed. Returns whether a job was created.
     *
     * **Its bound, stated rather than engineered around:** the exclusion binds the scope's live jobs, in two places —
     * here at the claim, and where the commit lands (`ScopesStore.rotate` asks [isRotationExcluded]). If they
     * dead-letter it lapses on both, until the rotator or an owner of the scope queues the rotation again through
     * this function; the window is visible to both as `FAILED` ([compromiseRotations]). A job a later removal
     * originates after a dead-letter does not carry it either; once any rotation lands without the rotator, their
     * behind grant still holds them at the commit.
     */
    @Transactional
    fun originateScopeRotationExcluding(scopeId: String, excludedWorkerId: String, maxAttempts: Int): Boolean {
        ScopeEntity.lockRow(scopeId)
        val created = pendingRotationJob(scopeId) == null
        if (created) createRotationJob(scopeId, maxAttempts)
        JobEntity.find(
            "scopeId = ?1 and capabilityTag = ?2 and status in ?3",
            scopeId, CapabilityTags.SCOPE_ROTATION, listOf(JobEntity.PENDING, JobEntity.LEASED),
        ).list().forEach { exclude(it.id, excludedWorkerId) }
        return created
    }

    /**
     * The scope's unclaimed rotation job, read under its row lock — never a `LEASED` one, live or expired. A leased
     * job's worker has already read its inputs (the directory comes right after the claim) and may already have
     * committed, so an event folded into it would be closed by a rotation that could not reflect it — and for an
     * account rotation, a directory read before the new identity was published seals the new epoch to the old one.
     * The lock makes a racing claim's compare-and-set on this row wait for the event to commit, so the claim, and the
     * directory read after it, come after the event.
     */
    private fun pendingRotationJob(scopeId: String): JobEntity? =
        JobEntity.find(
            "scopeId = ?1 and capabilityTag = ?2 and status = ?3",
            scopeId, CapabilityTags.SCOPE_ROTATION, JobEntity.PENDING,
        ).withLock(LockModeType.PESSIMISTIC_WRITE).list().firstOrNull()

    private fun createRotationJob(scopeId: String, maxAttempts: Int) {
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
    }

    private fun exclude(jobId: String, workerId: String) {
        if (JobDeclineEntity.count("jobId = ?1 and workerId = ?2", jobId, workerId) > 0L) return
        JobDeclineEntity().apply {
            id = UUID.randomUUID().toString()
            this.jobId = jobId
            this.workerId = workerId
            reason = ROTATION_ORIGINATOR
            declinedAt = Instant.now()
        }.persist()
    }

    /**
     * Whether a live (`PENDING`/`LEASED`) rotation job of [scopeId] carries [userId]'s rotation exclusion — the first
     * fact `rotateScope` refuses on (ADR 0060 Decision 4). Only the `rotation_originator` reason counts: a worker's own
     * `identity_changed`, `stale_epoch` or `not_scope_owner` decline is about one job, never about their authority.
     * An exhausted job whose lease expired still reads `LEASED` until a claim sweeps it, and counts: that errs closed.
     */
    fun isRotationExcluded(scopeId: String, userId: String): Boolean =
        JobDeclineEntity.count(
            "workerId = ?1 and reason = ?2 and jobId in " +
                "(select j.id from JobEntity j where j.scopeId = ?3 and j.capabilityTag = ?4 and j.status in ?5)",
            userId, ROTATION_ORIGINATOR, scopeId, CapabilityTags.SCOPE_ROTATION, listOf(JobEntity.PENDING, JobEntity.LEASED),
        ) > 0L

    /**
     * Record that [committerId] committed [epoch] on [scopeId] (#207): every rotation job of the scope **leased to
     * them** — `LEASED` with them as holder, no expiry test, exactly what [complete] means by holder — gets the mark
     * its completion requires. Runs in the commit's transaction, after the scope row was locked. A commit by anyone
     * else, or one made before the lease, marks nothing on the job.
     */
    @Transactional
    fun markRotated(scopeId: String, committerId: String, epoch: Int): Int =
        JobEntity.update(
            "rotatedEpoch = ?1 where scopeId = ?2 and capabilityTag = ?3 and status = ?4 and leaseHolder = ?5",
            epoch, scopeId, CapabilityTags.SCOPE_ROTATION, JobEntity.LEASED, committerId,
        )

    // --- the state of a queued compromise rotation (uc.account.rotate-keys §9, ADR 0060 Decision 6) ---------

    /**
     * Every compromise rotation queued on [scopeIds] (all scopes when null) by [rotatorId] (every rotator when null),
     * with its state. One row per (scope, rotator) where the rotator still holds a membership there: a scope they
     * left, or a purged one, drops out. The covering job is the scope's **newest** job carrying the rotator's
     * exclusion — an origination creates a job only when none is `PENDING`, so the newest live one is the one every
     * later event joined. Reads job, decline, membership and scope rows the server already holds; derives in
     * [CompromiseRotationPolicy].
     */
    fun compromiseRotations(scopeIds: Collection<String>? = null, rotatorId: String? = null): List<CompromiseRotationRow> {
        if (scopeIds != null && scopeIds.isEmpty()) return emptyList()
        val query = JobEntity.getEntityManager().createQuery(
            "select d.workerId, j.id from JobDeclineEntity d, JobEntity j where d.jobId = j.id and d.reason = :reason " +
                "and j.capabilityTag = :tag" + (if (scopeIds != null) " and j.scopeId in :scopes" else "") +
                (if (rotatorId != null) " and d.workerId = :rotator" else ""),
            Array<Any>::class.java,
        ).setParameter("reason", ROTATION_ORIGINATOR).setParameter("tag", CapabilityTags.SCOPE_ROTATION)
        scopeIds?.let { query.setParameter("scopes", it) }
        rotatorId?.let { query.setParameter("rotator", it) }
        val hits = query.resultList.map { it[0] as String to it[1] as String }
        if (hits.isEmpty()) return emptyList()

        val jobs = JobEntity.find("id in ?1", hits.map { it.second }.toSet()).list().associateBy { it.id }
        val covering = hits.mapNotNull { (rotator, jobId) -> jobs[jobId]?.let { rotator to it } }
            .groupBy({ (rotator, job) -> job.scopeId to rotator }, { it.second })
            .mapValues { (_, candidates) -> candidates.maxBy { it.createdAt } }
        val scopes = covering.keys.map { it.first }.toSet()
        val members = MembershipEntity.find("scopeId in ?1", scopes).list().groupBy { it.scopeId }
        val epochs = ScopeEntity.find("id in ?1", scopes).list().associate { it.id to it.currentEpoch }
        val decliners = JobDeclineEntity.find("jobId in ?1", covering.values.map { it.id }.toSet()).list()
            .groupBy({ it.jobId }, { it.workerId })
        val now = Instant.now()
        return covering.mapNotNull { (key, job) ->
            val (scopeId, rotator) = key
            val rows = members[scopeId].orEmpty()
            val own = rows.firstOrNull { it.userId == rotator } ?: return@mapNotNull null
            val declined = decliners[job.id].orEmpty().toSet()
            val facts = CompromiseRotationFacts(
                rotatorGrantedEpoch = own.wrappedGrant?.let { own.grantedEpoch },
                scopeCurrentEpoch = epochs[scopeId] ?: 0,
                jobStatus = job.status, jobLeaseExpiresAt = job.leaseExpiresAt,
                jobAttempts = job.attempts, jobMaxAttempts = job.maxAttempts,
                claimableByAnOwner = rows.any {
                    it.role == MembershipEntity.OWNER && it.wrappedGrant != null && it.userId !in declined
                },
            )
            CompromiseRotationRow(scopeId, rotator, CompromiseRotationPolicy.state(facts, now))
        }.sortedWith(compareBy({ it.scopeId }, { it.rotatorId }))
    }

    /**
     * Queue a dead-lettered compromise rotation of [scopeId] again — for [rotatorId] (the rotator themselves, D9), or
     * for every rotator whose rotation there is `FAILED` (an owner of the scope, D10). Under the scope row's lock, so
     * the state it gates on cannot move under it. Each failed rotator's rotation is originated excluding them again,
     * which re-arms `rotateScope`'s first refusal; nothing is written unless something is `FAILED`. The caller is not
     * excluded unless they are one of those rotators, so an owner's own worker can claim the job at once.
     */
    @Transactional
    fun requeueFailedRotations(scopeId: String, rotatorId: String?, maxAttempts: Int): RequeueResult {
        ScopeEntity.lockRow(scopeId)
        val rows = compromiseRotations(listOf(scopeId), rotatorId)
        if (rows.isEmpty()) return RequeueResult.NoRotation
        val failed = rows.filter { it.state == CompromiseRotationState.FAILED }
        if (failed.isEmpty()) return RequeueResult.NotFailed
        failed.forEach { originateScopeRotationExcluding(scopeId, it.rotatorId, maxAttempts) }
        return RequeueResult.Requeued(compromiseRotations(listOf(scopeId), rotatorId))
    }

    /**
     * Drop the caller's own declines against a contact whose changed identity key they just accepted
     * (ADR 0028 (2), ADR 0060 Decision 7).
     *
     * Nothing expires a decline, which is right while a key conflict is unresolved and wrong the moment a human
     * resolves it: without this, the accept authorises a wrap that nothing performs, and a rotated account is
     * stranded outside every scope whose deliverer had already declined.
     *
     * **Narrow in three ways, each load-bearing.** Only the caller's own rows (`workerId = ?1`) — the accept was
     * one human's decision on one client, and a blind server must not generalise it to a key-holder who has not
     * looked. Only jobs targeting `targetUserId` — a decline about a different contact is not resolved by this
     * accept. And only `grant-delivery` jobs, which is what keeps this away from the rotation exclusion above:
     * a `scope-rotation` job's `resourceRef` is a **scopeId** and it has no delivery target at all, so no
     * membership id can ever resolve to one. That invariant holds by the shape of this query rather than by a
     * special case inside it — which is why the tag filter is written even though the membership-id join would
     * already imply it.
     */
    @Transactional
    fun clearDeclinesForTarget(workerId: String, targetUserId: String): Int {
        val membershipIds = MembershipEntity.find("userId = ?1", targetUserId).list().map { it.id }
        if (membershipIds.isEmpty()) return 0
        val jobIds = JobEntity.find(
            "capabilityTag = ?1 and resourceRef in ?2", CapabilityTags.GRANT_DELIVERY, membershipIds,
        ).list().map { it.id }
        if (jobIds.isEmpty()) return 0
        return JobDeclineEntity.delete("workerId = ?1 and jobId in ?2", workerId, jobIds).toInt()
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

    /** Lazily dead-letter expired-lease jobs of one tag, on that tag's own eligible scopes, that have exhausted their
     *  attempts (a crashed poison worker). */
    private fun sweepDeadLetters(scopeIds: Set<String>, tag: String, now: Instant) {
        JobEntity.update(
            "status = ?1, leaseHolder = null, leaseExpiresAt = null where scopeId in ?2 and capabilityTag = ?3 " +
                "and status = ?4 and leaseExpiresAt < ?5 and attempts >= maxAttempts",
            JobEntity.FAILED, scopeIds, tag, JobEntity.LEASED, now,
        )
    }

    /** Resolve a grant-delivery job's target: the pending member's userId + identity public key. */
    private fun resolveGrantTarget(membershipId: String?): GrantDeliveryTargetView? {
        val m = membershipId?.let { MembershipEntity.findById(it) } ?: return null
        val user = VeyqUser.findById(m.userId) ?: return null
        return GrantDeliveryTargetView(m.userId, m.id, user.identityPublicKey, m.admission())
    }

    companion object {
        /** The `reason` on a decline written at origination rather than after a refusal — greppable, and it is
         *  what tells a rotation exclusion apart from a worker's own `identity_changed` decline. Only this reason
         *  binds `rotateScope` ([isRotationExcluded]) and names a compromise rotation ([compromiseRotations]). */
        const val ROTATION_ORIGINATOR = "rotation_originator"
    }
}
