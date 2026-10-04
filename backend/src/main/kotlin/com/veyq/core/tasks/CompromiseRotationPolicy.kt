package com.veyq.core.tasks

import java.time.Instant

/** Where a rotation an account rotation queued on one scope stands (uc.account.rotate-keys §9, ADR 0060 Decision 6). */
enum class CompromiseRotationState { WAITING, STALLED, SECURED, FAILED }

/** The facts one rotator's state on one scope is derived from — every one read from a row the server holds. */
data class CompromiseRotationFacts(
    /** The epoch the rotator's own grant on the scope holds, or null when they hold none. */
    val rotatorGrantedEpoch: Int?,
    val scopeCurrentEpoch: Int,
    /** The covering job: the scope's newest `scope-rotation` job that carries the rotator's exclusion. */
    val jobStatus: String,
    val jobLeaseExpiresAt: Instant?,
    val jobAttempts: Int,
    val jobMaxAttempts: Int,
    /** Whether some owner of the scope holds a grant there and has not declined the covering job. */
    val claimableByAnOwner: Boolean,
)

/**
 * The state of a queued compromise rotation, derived — never stored — so it cannot drift from the rows it reads.
 * Pure, so every rule is unit-testable; [TasksStore] gathers the facts.
 *
 * First match wins, and the order is the safety property: **`SECURED` is never answered while the old identity can
 * hold the scope's current epoch.**
 *  1. The rotator's own grant is behind or absent → `SECURED`, whatever the jobs say: the grant is the only thing the
 *     old identity could read the current epoch with. (It also covers a synchronous owner rotation with no job.)
 *  2. The covering job is `DONE` → `SECURED`: a rotation job completes only on its holder's commit during its lease,
 *     and every lease began after the identity change.
 *  3. The covering job is dead → `FAILED`: `FAILED`, or still `LEASED` with an expired lease and no attempts left
 *     (dead in fact; nothing claims it again, a later claim on its scope only sweeps it).
 *  4. It is live and no owner may claim it → `STALLED` (uc.account.rotate-keys E4).
 *  5. Otherwise `WAITING`.
 *
 * The error direction is the safe one: a rotator re-admitted after someone else's rotation can read `FAILED`, and a
 * re-queue then costs one redundant rotation.
 */
object CompromiseRotationPolicy {
    fun state(f: CompromiseRotationFacts, now: Instant): CompromiseRotationState = when {
        f.rotatorGrantedEpoch == null || f.rotatorGrantedEpoch < f.scopeCurrentEpoch -> CompromiseRotationState.SECURED
        f.jobStatus == JobEntity.DONE -> CompromiseRotationState.SECURED
        f.jobStatus == JobEntity.FAILED -> CompromiseRotationState.FAILED
        f.jobStatus == JobEntity.LEASED && f.jobLeaseExpiresAt?.isBefore(now) == true && f.jobAttempts >= f.jobMaxAttempts ->
            CompromiseRotationState.FAILED
        !f.claimableByAnOwner -> CompromiseRotationState.STALLED
        else -> CompromiseRotationState.WAITING
    }

    /**
     * What a scope's row shows its owners (D10): `FAILED` if any rotator's rotation failed, else `STALLED` if any
     * stalled, else nothing — `WAITING` needs nobody, `SECURED` needs nobody.
     */
    fun ownerView(states: Collection<CompromiseRotationState>): CompromiseRotationState? = when {
        CompromiseRotationState.FAILED in states -> CompromiseRotationState.FAILED
        CompromiseRotationState.STALLED in states -> CompromiseRotationState.STALLED
        else -> null
    }
}
