package me.raddatz.veyq.core.tasks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The rule [TasksStore.queuedRotations] picks a scope's newest rotation job by, pinned without a
 * database: `veyq_job.created_at` is `DATETIME(3)`, so a tie is two jobs inside one millisecond, and it must never
 * fall to the random id first (it did while the column was second-precision: a coin flip on MariaDB).
 */
class TasksStoreNewestCoveringTest {
    private val at = Instant.parse("2026-10-11T10:00:00.123Z")

    private fun job(id: String, status: String, createdAt: Instant = at) = JobEntity().also {
        it.id = id; it.status = status; it.createdAt = createdAt
    }

    private fun newest(vararg jobs: JobEntity) = jobs.toList().maxWith(TasksStore.NEWEST_COVERING).id

    @Test
    fun theLaterInstantWinsWhateverTheStatusAndId() {
        val older = job("ffffffff-0000-0000-0000-000000000000", JobEntity.PENDING, at)
        val newer = job("00000000-0000-0000-0000-000000000000", JobEntity.DONE, at.plusMillis(1))
        assertEquals(newer.id, newest(older, newer))
        assertEquals(newer.id, newest(newer, older))
    }

    @Test
    fun onATieTheLiveJobWinsThenFailedOverDoneThenTheId() {
        val lo = "00000000-0000-0000-0000-000000000001"
        val hi = "ffffffff-0000-0000-0000-000000000001"
        // each pair in both id orders, so the id is never what decides the first rules
        for ((winner, loser) in listOf(JobEntity.PENDING to JobEntity.FAILED, JobEntity.LEASED to JobEntity.DONE, JobEntity.FAILED to JobEntity.DONE)) {
            assertEquals(lo, newest(job(lo, winner), job(hi, loser)), "$winner beats $loser (low id)")
            assertEquals(hi, newest(job(lo, loser), job(hi, winner)), "$winner beats $loser (high id)")
        }
        assertEquals(hi, newest(job(lo, JobEntity.DONE), job(hi, JobEntity.DONE)), "equal in everything: the id, deterministically")
    }
}
