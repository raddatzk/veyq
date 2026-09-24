package com.veyq.app.features.admin.tenant

import com.veyq.app.shared.config.testVeyqConfig
import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord

/**
 * Two properties of the dev/test invite mailer, pinned together because they are the two halves of the same
 * secret-hygiene boundary (cr-invite-token-log-redaction):
 *  - the raw bootstrap token is **never** emitted to a log (`neverLogsTheRawBootstrapToken`);
 *  - it **is** still written to the opt-in dev-file the local seed orchestrator reads (cr-0046,
 *    `appendsOneJsonLinePerInviteWhenDevFileSet`).
 * `@QuarkusTest` so `LaunchMode.current()` is TEST (not NORMAL): the dev delivery path runs — i.e. exactly the
 * branch that historically logged the invite link with the raw token. The bean is constructed directly.
 */
@QuarkusTest
class TenantInviteMailerDevFileTest {

    @Test
    fun appendsOneJsonLinePerInviteWhenDevFileSet(@TempDir tmp: Path) {
        val file = tmp.resolve("nested/invites.jsonl") // parent created on demand
        val mailer = LoggingTenantInviteMailer(testVeyqConfig(mapOf("veyq.tenant.invite.dev-file" to file.toString())))

        mailer.sendInvite(TENANT_ID, "media.test", "owner@veyq.test", "BOOT-SECRET-123", Instant.parse("2026-07-06T00:00:00Z"))
        mailer.sendInvite(TENANT_ID, "media.test", "second@veyq.test", "BOOT-SECRET-456", Instant.parse("2026-07-06T00:00:00Z"))

        val lines = Files.readAllLines(file)
        assertEquals(2, lines.size, "one line appended per invite")
        assertTrue(lines[0].contains("\"token\":\"BOOT-SECRET-123\""), "first token recorded")
        assertTrue(lines[0].contains("\"ownerEmail\":\"owner@veyq.test\""), "first owner recorded")
        assertTrue(lines[1].contains("\"token\":\"BOOT-SECRET-456\""), "second token recorded")
    }

    @Test
    fun writesNoFileWhenDevFileUnset(@TempDir tmp: Path) {
        val file = tmp.resolve("never.jsonl")
        LoggingTenantInviteMailer(testVeyqConfig()).sendInvite(TENANT_ID, "media.test", "owner@veyq.test", "T", Instant.parse("2026-07-06T00:00:00Z"))
        assertFalse(Files.exists(file), "no file written when the config is empty (default)")
    }

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
            LoggingTenantInviteMailer(testVeyqConfig())
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
