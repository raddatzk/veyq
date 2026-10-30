package me.raddatz.veyq.core.testing

import org.jboss.logmanager.ExtFormatter
import org.jboss.logmanager.ExtLogRecord
import org.junit.jupiter.api.Assertions.assertTrue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Every log record the app emits while open, at the levels its loggers are configured for — attached to the root JUL
 * logger, where every framework's lines arrive too (Quarkus', Hibernate's, Vert.x'), so a test that asserts a value is
 * absent asserts it of every log line, not only of the app's own (ADR 0092 § 4).
 *
 * A record is rendered **whole**: the level, the logger, the formatted message, its parameters, and the thrown
 * exception's full stack trace — every cause's message with it, which is where a database driver's text rides.
 * [close] writes a control record and fails if the capture missed it, so a capture attached where no log goes cannot
 * pass a negative assertion as clean.
 */
class LogCapture : AutoCloseable {
    data class Record(val level: Level, val logger: String, val text: String)

    private val captured = CopyOnWriteArrayList<LogRecord>()
    private val root = Logger.getLogger("")
    private val handler = object : Handler() {
        override fun publish(record: LogRecord) { captured.add(record) }
        override fun flush() {}
        override fun close() {}
    }.apply { level = Level.ALL }

    init {
        root.addHandler(handler)
    }

    /** What was captured so far. */
    fun records(): List<Record> = captured.map { Record(it.level, it.loggerName.orEmpty(), render(it)) }

    /** Waits up to [timeoutMs] for a record [predicate] accepts — for a line written on another thread. */
    fun await(timeoutMs: Long = 10_000, predicate: (Record) -> Boolean): Record {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            records().firstOrNull(predicate)?.let { return it }
            Thread.sleep(20)
        }
        throw AssertionError("no matching log record within ${timeoutMs}ms; captured:\n${records().joinToString("\n")}")
    }

    override fun close() {
        try {
            Logger.getLogger("me.raddatz.veyq.core").info(CONTROL)
        } finally {
            root.removeHandler(handler)
        }
        assertTrue(captured.any { it.message == CONTROL }, "the capture missed the control record — it is not attached where the logs go")
    }

    private fun render(record: LogRecord): String {
        val message = (record as? ExtLogRecord)?.let(::formatted) ?: record.message.orEmpty()
        val parameters = record.parameters?.joinToString(prefix = " [", postfix = "]").orEmpty()
        val thrown = record.thrown?.stackTraceToString()?.let { "\n$it" }.orEmpty()
        return "${record.level} ${record.loggerName}: $message$parameters$thrown"
    }

    companion object {
        private const val CONTROL = "log capture control"

        /** [record]'s message with its parameters in, by the record's own format style: what the deprecated
         *  `ExtLogRecord.getFormattedMessage` answered, through the formatter jboss-logmanager 3 points to instead. */
        fun formatted(record: ExtLogRecord): String = MESSAGE.formatMessage(record).orEmpty()

        private val MESSAGE = object : ExtFormatter() {
            override fun format(record: ExtLogRecord): String = formatMessage(record)
        }
    }
}
