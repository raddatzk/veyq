package me.raddatz.veyq.core.tasks

import me.raddatz.veyq.core.tasks.CompromiseRotationState.FAILED
import me.raddatz.veyq.core.tasks.CompromiseRotationState.REKEYING
import me.raddatz.veyq.core.tasks.CompromiseRotationState.SECURED
import me.raddatz.veyq.core.tasks.CompromiseRotationState.STALLED
import me.raddatz.veyq.core.tasks.CompromiseRotationState.WAITING
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The derived state of a queued compromise rotation (uc.account.rotate-keys §9, ADR 0060 Decision 6), boot-free and
 * mutation-verified (ADR 0045 §2). The rule ORDER is the safety property — `SECURED` is never answered while the old
 * identity can still hold the scope's current epoch — so each rule is pinned twice: alone against a baseline that
 * answers `WAITING`, and against the facts of every rule below it, so a mutant that reorders, drops or widens an arm
 * turns a named case red. `RotationSliceTest` proves the facts reach it from the rows.
 */
class CompromiseRotationPolicyTest {

    private val now: Instant = Instant.parse("2026-10-03T12:00:00Z")

    /** Every fact at "a live rotation an owner may still complete": the rotator's grant at the current epoch, the
     *  covering job pending with attempts left and no lease. */
    private fun state(
        rotatorGrantedEpoch: Int? = 3, scopeCurrentEpoch: Int = 3, jobStatus: String = JobEntity.PENDING,
        jobLeaseExpiresAt: Instant? = null, jobAttempts: Int = 1, jobMaxAttempts: Int = 5, claimableByAnOwner: Boolean = true,
        nodesLag: Boolean = false,
    ) = CompromiseRotationPolicy.state(
        CompromiseRotationFacts(rotatorGrantedEpoch, scopeCurrentEpoch, jobStatus, jobLeaseExpiresAt, jobAttempts, jobMaxAttempts, claimableByAnOwner, nodesLag),
        now,
    )

    /** A lease that ran out before [now], on a job with no attempts left — dead in fact. */
    private fun deadLease(status: String = JobEntity.LEASED, expires: Instant = now.minusSeconds(1), attempts: Int = 5) =
        state(jobStatus = status, jobLeaseExpiresAt = expires, jobAttempts = attempts, jobMaxAttempts = 5)

    @Test
    fun aLiveRotationAnOwnerMayCompleteIsWaiting() {
        assertEquals(WAITING, state(), "the baseline every other case moves one fact away from")
    }

    // Rule 1 — the rotator's own grant is the only thing the old identity could read the current epoch with.

    @Test
    fun aGrantBehindTheScopeIsSecuredWhateverTheJobSays() {
        assertEquals(SECURED, state(rotatorGrantedEpoch = 2), "the covering rotation landed: the old identity holds no current key")
        assertEquals(SECURED, state(rotatorGrantedEpoch = 2, jobStatus = JobEntity.FAILED), "a stale FAILED job does not un-secure it")
        assertEquals(SECURED, state(rotatorGrantedEpoch = 2, claimableByAnOwner = false), "nor does a job nobody may claim")
    }

    @Test
    fun noGrantAtAllIsSecured() {
        assertEquals(SECURED, state(rotatorGrantedEpoch = null, jobStatus = JobEntity.FAILED, claimableByAnOwner = false),
            "a rotator holding no grant reads nothing there, whatever the job says")
    }

    @Test
    fun aGrantAtTheCurrentEpochIsNeverSecuredByTheGrantRule() {
        // The comparison is strict: a grant AT the current epoch is exactly what the old identity could read with.
        assertEquals(FAILED, state(rotatorGrantedEpoch = 3, jobStatus = JobEntity.FAILED), "equal epochs fall through to the job")
        assertEquals(FAILED, state(rotatorGrantedEpoch = 4, scopeCurrentEpoch = 3, jobStatus = JobEntity.FAILED),
            "a grant ahead of the scope's row is no sign the rotation landed")
    }

    // Rule 2 — a DONE job completed on its holder's commit, during a lease that began after the identity change.

    @Test
    fun aDoneCoveringJobIsSecured() {
        assertEquals(SECURED, state(jobStatus = JobEntity.DONE), "the holder's commit landed the new epoch")
        assertEquals(SECURED, state(jobStatus = JobEntity.DONE, claimableByAnOwner = false), "DONE outranks STALLED")
    }

    // Rule 3 — a dead job: FAILED, or LEASED with its lease run out and no attempts left.

    @Test
    fun aFailedCoveringJobIsFailed() {
        assertEquals(FAILED, state(jobStatus = JobEntity.FAILED), "it dead-lettered")
        assertEquals(FAILED, state(jobStatus = JobEntity.FAILED, claimableByAnOwner = false), "FAILED outranks STALLED")
    }

    @Test
    fun aLeasedJobWithItsLeaseRunOutAndNoAttemptsLeftIsFailed() {
        assertEquals(FAILED, deadLease(), "nothing claims it again: dead in fact")
        assertEquals(FAILED, deadLease(attempts = 6), "attempts past the maximum are no less dead")
    }

    @Test
    fun aLeasedJobIsDeadOnlyWhenEveryConditionHolds() {
        assertEquals(WAITING, deadLease(attempts = 4), "an attempt left: the lapsed lease is claimable again")
        assertEquals(WAITING, deadLease(expires = now), "a lease expiring exactly now has not run out yet")
        assertEquals(WAITING, deadLease(expires = now.plusSeconds(1)), "a lease still running is an owner at work")
        assertEquals(WAITING, state(jobStatus = JobEntity.LEASED, jobLeaseExpiresAt = null, jobAttempts = 5),
            "a lease with no expiry recorded is not a lapsed one")
        assertEquals(WAITING, deadLease(status = JobEntity.PENDING), "only a LEASED job is judged by its lease")
    }

    @Test
    fun aDeadLeaseIsFailedEvenWhenNoOwnerMayClaim() {
        assertEquals(FAILED, state(jobStatus = JobEntity.LEASED, jobLeaseExpiresAt = now.minusSeconds(1), jobAttempts = 5,
            claimableByAnOwner = false), "dead outranks stalled: the re-queue is the way out, not a promotion")
    }

    // Rules 4 and 5 — a live job: stalled when no owner may claim it, else waiting.

    @Test
    fun aLiveJobNoOwnerMayClaimIsStalled() {
        assertEquals(STALLED, state(claimableByAnOwner = false), "uc.account.rotate-keys E4: make someone else an owner")
        assertEquals(STALLED, state(jobStatus = JobEntity.LEASED, jobLeaseExpiresAt = now.minusSeconds(1), jobAttempts = 4,
            claimableByAnOwner = false), "a lapsed lease with attempts left is live, so stalled")
    }

    /** ADR 0109 point 1: a secured rotation whose re-key has not reached every node yet reads `REKEYING` — through
     *  either secured rule — and a lag changes no other state (it is not the rotator's grant that a node lags). */
    @Test
    fun aSecuredRotationWithANodeStillLaggingIsRekeying() {
        assertEquals(REKEYING, state(rotatorGrantedEpoch = 2, nodesLag = true), "the grant rule: the epoch is in, a node is not")
        assertEquals(REKEYING, state(jobStatus = JobEntity.DONE, nodesLag = true), "the done rule")
        assertEquals(SECURED, state(rotatorGrantedEpoch = 2, nodesLag = false), "control: no node lags")
        assertEquals(WAITING, state(nodesLag = true), "a waiting rotation stays waiting")
        assertEquals(FAILED, state(jobStatus = JobEntity.FAILED, nodesLag = true), "a failed one stays failed")
        assertEquals(STALLED, state(claimableByAnOwner = false, nodesLag = true), "a stalled one stays stalled")
        assertNull(CompromiseRotationPolicy.ownerView(listOf(REKEYING, WAITING)), "re-keying needs nobody: a worker resumes it")
    }

    // The owner's row (D10): the worst state that needs someone, or nothing.

    @Test
    fun anOwnerSeesFailedOverStalledAndNothingForStatesThatNeedNobody() {
        assertEquals(FAILED, CompromiseRotationPolicy.ownerView(listOf(FAILED)))
        assertEquals(FAILED, CompromiseRotationPolicy.ownerView(listOf(STALLED, FAILED, WAITING)), "FAILED outranks STALLED")
        assertEquals(STALLED, CompromiseRotationPolicy.ownerView(listOf(WAITING, STALLED, SECURED)))
        assertNull(CompromiseRotationPolicy.ownerView(listOf(WAITING, SECURED)), "waiting and secured need nobody")
        assertNull(CompromiseRotationPolicy.ownerView(emptyList()))
    }
}
