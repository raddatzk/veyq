package com.veyq.app.features.tasks

import com.veyq.app.shared.config.VeyqConfig
import com.veyq.app.shared.http.Failures
import com.veyq.app.shared.http.HttpFailure
import com.veyq.app.shared.membership.MembershipStore
import jakarta.enterprise.context.ApplicationScoped
import java.time.Duration

/** Outcome of a client-originated enqueue (cr-0162 gave it the membership boundary; ADR 0042). */
sealed interface EnqueueOutcome {
    data class Created(val jobId: String) : EnqueueOutcome
    data object ScopeNotFound : EnqueueOutcome, HttpFailure by Failures.scopeNotFound
    data object NotKeyHolder : EnqueueOutcome, HttpFailure by Failures.notKeyHolder
}

/**
 * Use-case logic for the background-task pull queue (ADR 0021, cr-0076). Thin over [TasksStore]; it owns the
 * lease TTL + max-attempts policy (config) and passes them down. Eligibility ("holds the scope key") and the
 * atomic claim/lease live in the store; operator-blindness is structural — nothing here interprets a body.
 */
@ApplicationScoped
class TasksHandler(
    private val store: TasksStore,
    private val membership: MembershipStore,
    config: VeyqConfig,
) {
    private val leaseTtl: Duration = config.tasks().lease().ttl()
    private val maxAttempts: Int = config.tasks().maxAttempts()

    fun claim(userId: String, tags: List<String>, scopeFilter: String?): ClaimedJobView? =
        store.claim(userId, tags, scopeFilter, leaseTtl, maxAttempts)

    fun heartbeat(userId: String, jobId: String): HeartbeatResult =
        store.heartbeat(userId, jobId, leaseTtl)

    fun complete(userId: String, jobId: String, sealedResult: String?): CompleteResult =
        store.complete(userId, jobId, sealedResult)

    fun fail(userId: String, jobId: String): FailResult =
        store.fail(userId, jobId)

    fun decline(userId: String, jobId: String, reason: String?): DeclineResult =
        store.decline(userId, jobId, reason)

    /**
     * Enqueue a client-originated job. The **membership boundary is the first guard** (ADR 0042): a non-member
     * gets the same 404 as a bogus scope id, so scope existence is not enumerable. Only then does the store's
     * grant gate run — a member who holds no grant could not have sealed the body anyway, and gets a 403 that
     * discloses nothing, because they already know the scope exists.
     */
    fun enqueue(userId: String, scopeId: String, capabilityTag: String, resourceRef: String?, sealedBody: String?): EnqueueOutcome {
        if (!membership.isScopeMember(userId, scopeId)) return EnqueueOutcome.ScopeNotFound
        val jobId = store.enqueue(userId, scopeId, capabilityTag, resourceRef, sealedBody, maxAttempts)
            ?: return EnqueueOutcome.NotKeyHolder
        return EnqueueOutcome.Created(jobId)
    }
}
