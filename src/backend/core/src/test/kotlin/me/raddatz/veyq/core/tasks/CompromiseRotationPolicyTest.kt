package me.raddatz.veyq.core.tasks

import me.raddatz.veyq.core.tasks.CompromiseRotationState.FAILED
import me.raddatz.veyq.core.tasks.CompromiseRotationState.STALLED
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The owner view of a queued rotation (uc.spaces.complete-queued-rotation; a removal cascade's job, ADR 0025), boot-free
 * and mutation-verified (ADR 0045 §2). The rule ORDER is the property — a done job needs nobody, a dead one outranks a
 * stalled one — so each rule is pinned alone against a baseline that answers nothing, and against the facts of every rule
 * below it, so a mutant that reorders, drops or widens an arm turns a named case red. `QueuedRotationSliceTest` proves the
 * facts reach it from the rows.
 */
class CompromiseRotationPolicyTest {

    private val now: Instant = Instant.parse("2026-10-03T12:00:00Z")

    /** Every fact at "a live rotation an owner may still complete": pending, attempts left, no lease. */
    private fun view(
        jobStatus: String = JobEntity.PENDING, jobLeaseExpiresAt: Instant? = null, jobAttempts: Int = 1, jobMaxAttempts: Int = 5,
        claimableByAnOwner: Boolean = true,
    ) = CompromiseRotationPolicy.ownerView(
        QueuedRotationFacts(jobStatus, jobLeaseExpiresAt, jobAttempts, jobMaxAttempts, claimableByAnOwner),
        now,
    )

    /** A lease that ran out before [now], on a job with no attempts left — dead in fact. */
    private fun deadLease(status: String = JobEntity.LEASED, expires: Instant = now.minusSeconds(1), attempts: Int = 5, claimable: Boolean = true) =
        view(jobStatus = status, jobLeaseExpiresAt = expires, jobAttempts = attempts, jobMaxAttempts = 5, claimableByAnOwner = claimable)

    @Test
    fun aLiveRotationAnOwnerMayCompleteNeedsNobody() {
        assertNull(view(), "the baseline every other case moves one fact away from")
    }

    @Test
    fun aDoneJobNeedsNobody() {
        assertNull(view(jobStatus = JobEntity.DONE), "the holder's commit and re-key landed")
        assertNull(view(jobStatus = JobEntity.DONE, claimableByAnOwner = false), "DONE outranks STALLED")
    }

    @Test
    fun aFailedJobIsFailed() {
        assertEquals(FAILED, view(jobStatus = JobEntity.FAILED), "it dead-lettered")
        assertEquals(FAILED, view(jobStatus = JobEntity.FAILED, claimableByAnOwner = false), "FAILED outranks STALLED")
    }

    @Test
    fun aLeasedJobWithItsLeaseRunOutAndNoAttemptsLeftIsFailed() {
        assertEquals(FAILED, deadLease(), "nothing claims it again: dead in fact")
        assertEquals(FAILED, deadLease(attempts = 6), "attempts past the maximum are no less dead")
        assertEquals(FAILED, deadLease(claimable = false), "dead outranks stalled: the re-queue is the way out, not a promotion")
    }

    @Test
    fun aLeasedJobIsDeadOnlyWhenEveryConditionHolds() {
        assertNull(deadLease(attempts = 4), "an attempt left: the lapsed lease is claimable again")
        assertNull(deadLease(expires = now), "a lease expiring exactly now has not run out yet")
        assertNull(deadLease(expires = now.plusSeconds(1)), "a lease still running is an owner at work")
        assertNull(view(jobStatus = JobEntity.LEASED, jobLeaseExpiresAt = null, jobAttempts = 5), "a lease with no expiry recorded is not a lapsed one")
        assertNull(deadLease(status = JobEntity.PENDING), "only a LEASED job is judged by its lease")
    }

    @Test
    fun aLiveJobNoOwnerMayClaimIsStalled() {
        assertEquals(STALLED, view(claimableByAnOwner = false), "make someone else an owner")
        assertEquals(STALLED, deadLease(attempts = 4, claimable = false), "a lapsed lease with attempts left is live, so stalled")
    }
}
