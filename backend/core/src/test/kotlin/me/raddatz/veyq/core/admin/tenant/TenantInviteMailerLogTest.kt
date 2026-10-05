package com.veyq.core.admin.tenant

import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord

/**
 * The dev/test invite mailer never emits the raw bootstrap token to a log (cr-invite-token-log-redaction).
 * `@QuarkusTest` so `LaunchMode.current()` is TEST (not NORMAL): the dev delivery path runs — i.e. exactly the
 * branch that historically logged the invite link with the raw token. The bean is constructed directly.
 */
@QuarkusTest
class TenantInviteMailerLogTest {

    /**
     * The raw redemption token must never reach a log record. Capture everything the mailer's logger emits
     * during a delivery and assert the token appears in no record's message or parameters. This bites: restore
     * the old `log.infof(... link)` and the token rides the link parameter into a captured record.
     */
    @Test
    fun neverLogsTheRawBootstrapToken() {
        val token = "BOOT-SECRET-DO-NOT-LOG-789"
        val jul = java.util.logging.Logger.getLogger(LoggingTenantInviteMailer::class.java.name)
        val records = mutableListOf<LogRecord>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { records.add(record) }
            override fun flush() {}
            override fun close() {}
        }
        handler.level = Level.ALL
        val previousLevel = jul.level
        jul.level = Level.ALL
        jul.addHandler(handler)
        try {
            LoggingTenantInviteMailer()
                .sendInvite(TENANT_ID, "media.test", "owner@veyq.test", token, Instant.parse("2026-07-06T00:00:00Z"))
        } finally {
            jul.removeHandler(handler)
            jul.level = previousLevel
        }

        assertTrue(records.isNotEmpty(), "the dev delivery path should emit a log record (non-NORMAL launch mode)")
        records.forEach { r ->
            val emitted = buildString {
                append(r.message ?: "")
                r.parameters?.forEach { append(' ').append(it?.toString() ?: "") }
            }
            assertFalse(emitted.contains(token), "raw invite token must never appear in a log record: <$emitted>")
        }
    }

    private companion object {
        /** The logging mailer never reads settings, so any tenant id will do; the domain is what it validates. */
        const val TENANT_ID = "00000000-0000-0000-0000-000000000001"
    }
}
