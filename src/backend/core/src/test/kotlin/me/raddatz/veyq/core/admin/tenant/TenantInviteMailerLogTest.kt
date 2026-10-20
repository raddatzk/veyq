package me.raddatz.veyq.core.admin.tenant

import io.quarkus.runtime.LaunchMode
import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord

/**
 * The dev/test invite mailer never emits the raw bootstrap token (cr-invite-token-log-redaction) or the owner's
 * address (ADR 0092 § 3) to a log. `@QuarkusTest` so `LaunchMode.current()` is TEST (not NORMAL): the dev delivery
 * path runs — i.e. exactly the branch that historically logged the invite link with the raw token. The production
 * branch is reached by handing the bean a NORMAL launch mode. The bean is constructed directly.
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
        val emitted = capture { LoggingTenantInviteMailer().sendInvite(TENANT_ID, "media.test", OWNER, token, EXPIRES) }

        assertTrue(emitted.isNotEmpty(), "the dev delivery path should emit a log record (non-NORMAL launch mode)")
        emitted.forEach { assertFalse(it.contains(token), "raw invite token must never appear in a log record: <$it>") }
    }

    /**
     * Neither branch names the owner: each logs the tenant's id (the positive control — the line is there and says
     * whose invite it was) and never the address. Bites on either branch: put `ownerEmail` back into its `warnf` or
     * `infof` and that branch's case goes red.
     */
    @Test
    fun namesTheTenantNeverTheOwnerInEitherLaunchMode() {
        for (mode in listOf(LaunchMode.TEST, LaunchMode.NORMAL)) {
            val emitted = capture {
                LoggingTenantInviteMailer { mode }.sendInvite(TENANT_ID, "media.test", OWNER, "BOOT-SECRET-1", EXPIRES)
            }
            assertTrue(emitted.any { it.contains(TENANT_ID) }, "$mode: the line names the tenant by id: $emitted")
            emitted.forEach { assertFalse(it.contains(OWNER), "$mode: the owner's address is in a log record: <$it>") }
        }
    }

    /** Every record the mailer's logger emits during [block], as its message followed by its parameters. */
    private fun capture(block: () -> Unit): List<String> {
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
            block()
        } finally {
            jul.removeHandler(handler)
            jul.level = previousLevel
        }
        return records.map { r ->
            buildString {
                append(r.message ?: "")
                r.parameters?.forEach { append(' ').append(it?.toString() ?: "") }
            }
        }
    }

    private companion object {
        /** The logging mailer never reads settings, so any tenant id will do; the domain is what it validates. */
        const val TENANT_ID = "00000000-0000-0000-0000-000000000001"
        const val OWNER = "owner@veyq.test"
        val EXPIRES: Instant = Instant.parse("2026-07-06T00:00:00Z")
    }
}
