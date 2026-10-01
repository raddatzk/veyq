package com.veyq.app.features.account.recovery

import com.veyq.app.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The scheduled recovery-token sweep, through its real entry `RecoveryPurge.run()` (ADR 0054): overdue PENDING
 * tokens are flipped to EXPIRED **first**, and only then are terminal rows past the `%test` retention (the
 * default `P7D`) deleted.
 *
 * The suite's scheduler is halted (`ScheduledJobsHaltedInTestTest`), so this test is the only place `run()`
 * executes. Each row belongs to its own account, which keeps the fixture inside the "at most one PENDING per
 * user" rule. The assertions run in row order on purpose: R2 reddens first when the expire step is gone, R5 when
 * the two steps run the other way round.
 *
 * Not asserted: the `@Transactional` on `run()`. Its comment says the second nested call would otherwise fail with
 * "EntityManager is closed" on the scheduler thread (cr-0053); on a test thread both store calls open their own
 * transaction and pass either way, so that is a property of the scheduler thread only, and no test here pins it.
 */
@QuarkusTest
class RecoveryPurgeTest {

    @Inject
    lateinit var purge: RecoveryPurge

    @Test
    fun overdueTokensAreExpiredFirstAndTerminalRowsPastRetentionAreDeleted() {
        val now = Instant.now()
        val r1 = token(AccountRecoveryEntity.PENDING, createdAt = now, expiresAt = now.plus(1, ChronoUnit.HOURS))
        val r2 = token(
            AccountRecoveryEntity.PENDING,
            createdAt = now.minus(1, ChronoUnit.HOURS), expiresAt = now.minus(1, ChronoUnit.MINUTES),
        )
        val r3 = token(AccountRecoveryEntity.CONSUMED, createdAt = now.minus(8, ChronoUnit.DAYS), expiresAt = now.minus(8, ChronoUnit.DAYS))
        val r4 = token(AccountRecoveryEntity.CONSUMED, createdAt = now.minus(1, ChronoUnit.DAYS), expiresAt = now.minus(1, ChronoUnit.DAYS))
        // Created and overdue before the retention cutoff, but still PENDING: only "expire, then purge" deletes it
        // in one run. Purge first, and it is not terminal yet, so it survives as EXPIRED.
        val eightDaysAgo = now.minus(8, ChronoUnit.DAYS)
        val r5 = token(AccountRecoveryEntity.PENDING, createdAt = eightDaysAgo, expiresAt = eightDaysAgo.plus(1, ChronoUnit.HOURS))

        purge.run()

        assertEquals(AccountRecoveryEntity.PENDING, statusOf(r1), "R1: a token inside its TTL stays PENDING")
        assertEquals(AccountRecoveryEntity.EXPIRED, statusOf(r2), "R2: an overdue token is flipped to EXPIRED and kept inside retention")
        assertNull(statusOf(r3), "R3: a terminal row past retention is deleted")
        assertEquals(AccountRecoveryEntity.CONSUMED, statusOf(r4), "R4: a terminal row inside retention is kept")
        assertNull(statusOf(r5), "R5: an overdue PENDING row past retention is expired, then deleted in the same run")
    }

    /** One token for a fresh account. Returns the row id. */
    private fun token(status: String, createdAt: Instant, expiresAt: Instant): String {
        val userId = seedUser()
        val id = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            AccountRecoveryEntity().apply {
                this.id = id
                this.userId = userId
                email = "recovery-purge-$id@example.com"
                tokenHash = UUID.randomUUID().toString().replace("-", "")
                this.status = status
                this.expiresAt = expiresAt
                this.createdAt = createdAt
            }.persist()
        }
        return id
    }

    private fun statusOf(id: String): String? =
        QuarkusTransaction.requiringNew().call { AccountRecoveryEntity.findById(id)?.status }
}
