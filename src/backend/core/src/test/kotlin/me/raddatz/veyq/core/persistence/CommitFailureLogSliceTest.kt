package me.raddatz.veyq.core.persistence

import com.arjuna.ats.arjuna.common.Uid
import com.arjuna.ats.arjuna.logging.tsLogger
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import me.raddatz.veyq.core.testing.LogCapture
import me.raddatz.veyq.core.testing.TestFixtures.newUser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.logging.Level

/**
 * A constraint violation that surfaces only at **commit** — no explicit flush, so Hibernate's insert runs in the
 * transaction manager's `beforeCompletion` — is logged by Narayana as `ARJUNA012125` at WARN on `com.arjuna.ats.arjuna`,
 * with the converted exception whose text carries the database driver's message, and that message names the value the
 * statement collided on: an email address on `ux_veyq_user_email` (ADR 0092 § 3, § 4). [CommitFailureLogFilter]
 * rewrites that one line to its class chain and SQLState; every other line of the category passes as it was, the
 * transaction reaper's timeout warnings among them.
 *
 * Red without the filter (its installation removed, the WARN carries the address in its exception), and each negative
 * has its control on the same capture: the `ARJUNA012125` line IS written, and names the SQLState.
 */
@QuarkusTest
class CommitFailureLogSliceTest {

    @Test
    fun aViolationAtCommitIsLoggedByClassChainNeverByTheValueItCollidedOn() {
        val email = "commit-collide-${UUID.randomUUID()}@example.org"
        QuarkusTransaction.requiringNew().run { newUser(email = email).persist() }

        var failure: Throwable? = null
        val records = LogCapture().use { capture ->
            // No flush: the insert runs at commit, inside Narayana's beforeCompletion.
            failure = runCatching { QuarkusTransaction.requiringNew().run { newUser(email = email).persist() } }.exceptionOrNull()
            capture.records()
        }

        assertNotNull(failure, "the second account on the same address must not commit")
        records.forEach { assertFalse(email in it.text, "<$email> is in a log record: <$it>") }
        val line = records.single { it.logger == ARJUNA && it.level == Level.WARNING && "ARJUNA012125" in it.text }
        assertTrue(Regex("SQLState 23\\d{3}, vendor code").containsMatchIn(line.text), "the database's verdict survives as its SQLState: <$line>")
        assertTrue("ConstraintViolationException" in line.text, "the line names the class chain: <$line>")
    }

    @Test
    fun anotherArjunaWarningPassesUnchanged() {
        val uid = Uid()
        val state = "probe-state-${UUID.randomUUID()}"
        val records = LogCapture().use { capture ->
            tsLogger.i18NLogger.warn_coordinator_TransactionReaper_18(uid, state)
            capture.records()
        }

        val line = records.single { it.logger == ARJUNA && "ARJUNA012117" in it.text }
        assertEquals(Level.WARNING, line.level)
        assertTrue(uid.toString() in line.text && state in line.text, "the reaper's line keeps its parameters: <$line>")
    }

    @Test
    fun theFilterRewritesOnlyItsOwnLine() {
        val thrown = IllegalStateException("driver text naming someone@example.org")
        val own = record("ARJUNA012125: TwoPhaseCoordinator.beforeCompletion - failed for %s", thrown)
        assertTrue(CommitFailureLogFilter.isLoggable(own))
        assertNull(own.thrown, "the exception and its message are dropped")
        val message = LogCapture.formatted(own)
        assertFalse("someone@example.org" in message)
        assertTrue(IllegalStateException::class.java.name in message, "<$message>")

        val other = record("ARJUNA012117: TransactionReaper::check processing TX %s in state  %s", thrown)
        assertTrue(CommitFailureLogFilter.isLoggable(other))
        assertEquals(thrown, other.thrown, "another line keeps its exception")
    }

    private fun record(format: String, thrown: Throwable) =
        org.jboss.logmanager.ExtLogRecord(java.util.logging.Level.WARNING, format, org.jboss.logmanager.ExtLogRecord.FormatStyle.PRINTF, javaClass.name).apply {
            parameters = arrayOf("p0", "p1")
            this.thrown = thrown
            loggerName = ARJUNA
        }

    private companion object {
        const val ARJUNA = "com.arjuna.ats.arjuna"
    }
}
