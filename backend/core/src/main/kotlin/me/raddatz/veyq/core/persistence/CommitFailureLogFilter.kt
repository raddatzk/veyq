package me.raddatz.veyq.core.persistence

import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import me.raddatz.veyq.core.http.UnexpectedError
import org.jboss.logmanager.ExtLogRecord
import java.util.logging.Filter
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Narayana's one line that carries someone else's text (ADR 0092 § 3, § 4): when a constraint violation surfaces only
 * at **commit** — Hibernate flushes inside the transaction manager's `beforeCompletion` — `com.arjuna.ats.arjuna` logs
 * `ARJUNA012125` ("TwoPhaseCoordinator.beforeCompletion - failed for …") at WARN with the converted exception, whose
 * message and causes carry the database driver's message, and that names the value the statement was bound with: an
 * email address on `ux_veyq_user_email` when two email changes race. This filter rewrites that record in place to the
 * exception's class chain, its SQLState and vendor code and the first own frame ([UnexpectedError.describe]) and drops
 * the exception; the caller still receives it whole and answers through the unexpected-error handler.
 *
 * **Only that line.** The category is not silenced the way Hibernate's statement log is: it also carries the
 * transaction reaper's timeout warnings (`ARJUNA012117` and its neighbours), which an operator needs, and every other
 * record passes untouched.
 *
 * **On the logger, not on a handler.** A Quarkus `@LoggingFilter` is linked to a handler (`quarkus.log.console.filter`),
 * so a file, syslog or socket handler an operator adds would still write the original; a logger's filter runs before
 * any handler sees the record, so every handler — and the root-logger capture the log tests read — gets the rewritten
 * one. Narayana logs this message through `tsLogger`, whose logger is exactly [CATEGORY]; a JUL logger's filter does
 * not reach a child logger's records, and Narayana writes none there for this message.
 *
 * Held by `CommitFailureLogSliceTest`: a duplicate committed without a flush leaves no address in any record, and the
 * reaper's warning keeps its parameters.
 */
object CommitFailureLogFilter : Filter {
    const val CATEGORY = "com.arjuna.ats.arjuna"
    private const val MESSAGE_ID = "ARJUNA012125"

    override fun isLoggable(record: LogRecord): Boolean {
        val thrown = record.thrown ?: return true
        if (record.message?.startsWith(MESSAGE_ID) != true) return true
        val text = "$MESSAGE_ID: TwoPhaseCoordinator.beforeCompletion failed: ${UnexpectedError.describe(thrown)}"
        record.thrown = null
        record.parameters = null
        if (record is ExtLogRecord) record.setMessage(text, ExtLogRecord.FormatStyle.NO_FORMAT) else record.message = text
        return true
    }
}

/** Installs [CommitFailureLogFilter] on Narayana's category at startup, before any request can commit. */
@ApplicationScoped
class CommitFailureLogInstaller {
    fun install(@Observes event: StartupEvent) {
        Logger.getLogger(CommitFailureLogFilter.CATEGORY).filter = CommitFailureLogFilter
    }
}
