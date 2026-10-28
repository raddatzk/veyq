package me.raddatz.veyq.core.tasks

import java.time.Instant

/**
 * What a rotation queued on a scope needs from its owners (uc.spaces.complete-queued-rotation): a tenant member's
 * removal queues one for each group the removed member shared (ADR 0025, `TenantHandler`'s cut). An account rotation
 * queues none any more — the account mints its scopes' next epochs itself (cr-secure-the-account decision 4) — so
 * nothing here names a rotator.
 */
enum class CompromiseRotationState { STALLED, FAILED }

/** The facts the owner view of one scope is derived from — every one read from a row the server holds. */
data class QueuedRotationFacts(
    /** The scope's newest `scope-rotation` job ([TasksStore.NEWEST_COVERING]): every later event joined it. */
    val jobStatus: String,
    val jobLeaseExpiresAt: Instant?,
    val jobAttempts: Int,
    val jobMaxAttempts: Int,
    /** Whether some owner of the scope holds a grant there and has not declined the job. */
    val claimableByAnOwner: Boolean,
)

/**
 * The owner view of a queued rotation, derived — never stored — so it cannot drift from the rows it reads. Pure, so
 * every rule is unit-testable; [TasksStore] gathers the facts. First match wins:
 *  1. The job is `DONE` → nothing needs an owner: a rotation job completes only on its holder's commit and re-key.
 *  2. The job is dead → `FAILED`: `FAILED`, or still `LEASED` with an expired lease and no attempts left (dead in fact;
 *     nothing claims it again, a later claim on its scope only sweeps it). An owner queues it again.
 *  3. It is live and no owner may claim it → `STALLED`: promote a co-owner.
 *  4. Otherwise nothing: it waits for an owner's worker.
 *
 * The error direction is the safe one: a scope an owner rotated by hand after the job dead-lettered still reads
 * `FAILED`, and a re-queue then costs one redundant rotation.
 */
object CompromiseRotationPolicy {
    fun ownerView(f: QueuedRotationFacts, now: Instant): CompromiseRotationState? = when {
        f.jobStatus == JobEntity.DONE -> null
        f.jobStatus == JobEntity.FAILED -> CompromiseRotationState.FAILED
        f.jobStatus == JobEntity.LEASED && f.jobLeaseExpiresAt?.isBefore(now) == true && f.jobAttempts >= f.jobMaxAttempts ->
            CompromiseRotationState.FAILED
        !f.claimableByAnOwner -> CompromiseRotationState.STALLED
        else -> null
    }
}
