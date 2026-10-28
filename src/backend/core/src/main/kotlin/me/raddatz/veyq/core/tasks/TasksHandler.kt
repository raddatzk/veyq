package me.raddatz.veyq.core.tasks

import me.raddatz.veyq.core.config.VeyqConfig
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.membership.AccessMode.READ
import me.raddatz.veyq.core.membership.AccessMode.WRITE
import me.raddatz.veyq.core.membership.ScopeAccess
import me.raddatz.veyq.core.membership.refusal
import jakarta.enterprise.context.ApplicationScoped
import java.time.Duration

/** Outcome of a client-originated enqueue (cr-0162 gave it the membership boundary; ADR 0042). */
sealed interface EnqueueOutcome {
    data class Created(val jobId: String) : EnqueueOutcome
    data object Suspended : EnqueueOutcome, HttpFailure by Failures.tenantSuspended
    data object Deleting : EnqueueOutcome, HttpFailure by Failures.scopeDeleting
    data object ScopeNotFound : EnqueueOutcome, HttpFailure by Failures.scopeNotFound
    data object NotKeyHolder : EnqueueOutcome, HttpFailure by Failures.notKeyHolder
}

/**
 * Use-case logic for the background-task pull queue (ADR 0021, cr-0076). Thin over [TasksStore]; it owns the
 * lease TTL + max-attempts policy (config) and passes them down. Every job operation asks the scope-access gate
 * first — a job is worked by writing into its space (uc.spaces.scope-access A5); eligibility ("holds the scope
 * key") and the atomic claim/lease live in the store. Operator-blindness is structural — nothing here interprets a
 * body.
 */
@ApplicationScoped
class TasksHandler(
    private val store: TasksStore,
    private val access: ScopeAccess,
    config: VeyqConfig,
) {
    private val leaseTtl: Duration = config.tasks().lease().ttl()
    private val maxAttempts: Int = config.tasks().maxAttempts()

    /**
     * Lease one job. Working a job is a write on its space (uc.spaces.scope-access A5), so the eligible scopes are the
     * caller's granted ones the gate lets them **write** — a suspended tenant's or a deleting group's jobs are simply
     * not handed out; they wait for the reactivation or the restore, or are erased with the purge.
     *
     * Eligibility is **per tag** (ADR 0021 §4): a `scope-rotation` job is offered only on a scope the caller also
     * **owns**. Only an owner may commit a rotation (ADR 0014 §4), so a non-owner's claim spent one of the job's
     * attempts on a commit refused `not_scope_owner`, and in a scope whose online members were mostly not owners the
     * job dead-lettered before an owner came online (#180).
     */
    fun claim(userId: String, tags: List<String>, scopeFilter: String?): ClaimedJobView? {
        val granted = store.grantedScopeIds(userId).let { g -> if (scopeFilter != null) g intersect setOf(scopeFilter) else g }
        val writable = access.writable(userId, granted)
        val owned by lazy { writable intersect store.ownedScopeIds(userId) }
        val eligibleByTag = tags.distinct().associateWith { tag -> if (tag == CapabilityTags.SCOPE_ROTATION) owned else writable }
        return store.claim(userId, eligibleByTag, leaseTtl, maxAttempts)
    }

    fun heartbeat(userId: String, jobId: String): HeartbeatResult {
        val job = store.find(jobId) ?: return HeartbeatResult.NotFound
        access.check(userId, job.scopeId, WRITE)
            .refusal(HeartbeatResult.NotFound, HeartbeatResult.Suspended, HeartbeatResult.Deleting)?.let { return it }
        return store.heartbeat(userId, jobId, leaseTtl)
    }

    fun complete(
        userId: String, jobId: String, sealedResult: String?, statedEpoch: Int?, grantSignature: String?, recipientIdentityDigest: String?,
    ): CompleteResult {
        val job = store.find(jobId) ?: return CompleteResult.NotFound
        access.check(userId, job.scopeId, WRITE)
            .refusal(CompleteResult.NotFound, CompleteResult.Suspended, CompleteResult.Deleting)?.let { return it }
        return store.complete(userId, jobId, sealedResult, statedEpoch, grantSignature, recipientIdentityDigest)
    }

    fun fail(userId: String, jobId: String): FailResult {
        val job = store.find(jobId) ?: return FailResult.NotFound
        access.check(userId, job.scopeId, WRITE)
            .refusal(FailResult.NotFound, FailResult.Suspended, FailResult.Deleting)?.let { return it }
        return store.fail(userId, jobId)
    }

    fun decline(userId: String, jobId: String, reason: String?): DeclineResult {
        val job = store.find(jobId) ?: return DeclineResult.NotFound
        access.check(userId, job.scopeId, WRITE)
            .refusal(DeclineResult.NotFound, DeclineResult.Suspended, DeclineResult.Deleting)?.let { return it }
        return store.decline(userId, jobId, reason)
    }

    /**
     * Enqueue a client-originated job. The **scope-access gate is the first guard** (ADR 0042): a non-member
     * gets the same 404 as a bogus scope id, so scope existence is not enumerable. Only then does the store's
     * grant gate run — a member who holds no grant could not have sealed the body anyway, and gets a 403 that
     * discloses nothing, because they already know the scope exists.
     */
    fun enqueue(userId: String, scopeId: String, capabilityTag: String, resourceRef: String?, sealedBody: String?): EnqueueOutcome {
        access.check(userId, scopeId, WRITE)
            .refusal(EnqueueOutcome.ScopeNotFound, EnqueueOutcome.Suspended, EnqueueOutcome.Deleting)?.let { return it }
        val jobId = store.enqueue(userId, scopeId, capabilityTag, resourceRef, sealedBody, maxAttempts)
            ?: return EnqueueOutcome.NotKeyHolder
        return EnqueueOutcome.Created(jobId)
    }
}
