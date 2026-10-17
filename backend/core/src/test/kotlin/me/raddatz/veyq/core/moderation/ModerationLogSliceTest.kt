package me.raddatz.veyq.core.moderation

import me.raddatz.veyq.core.moderation.ModerationTestKit.appointAndAccept
import me.raddatz.veyq.core.moderation.ModerationTestKit.asOwner
import me.raddatz.veyq.core.moderation.ModerationTestKit.asUser
import me.raddatz.veyq.core.moderation.ModerationTestKit.enable
import me.raddatz.veyq.core.moderation.ModerationTestKit.epochKey
import me.raddatz.veyq.core.moderation.ModerationTestKit.proof
import me.raddatz.veyq.core.moderation.ModerationTestKit.sealedGrant
import me.raddatz.veyq.core.testing.TestFixtures.IDENTITY_PUBLIC_KEY
import me.raddatz.veyq.core.testing.TestFixtures.ensureUser
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * The moderation log's instance events (ADR 0093 § 11, ADR 0099 § 4, en.moderation.log): each written in its cause's
 * transaction, kept to 31 December of the third year after the moderator's removal, purged after, and never in a log
 * line.
 */
@QuarkusTest
class ModerationLogSliceTest {

    @Inject
    lateinit var handler: ModerationHandler

    @Inject
    lateinit var log: ModerationLog

    @Inject
    lateinit var purge: ModerationPurge

    private lateinit var deputy: String
    private lateinit var address: String

    @BeforeEach
    fun seed() {
        ModerationTestKit.reset()
        ensureUser(OWNER, "log-owner@example.com", "SERVER_OWNER")
        deputy = UUID.randomUUID().toString()
        address = "log-deputy-$deputy@example.com"
        seedUser(id = deputy, email = address)
    }

    @Test
    fun theRetentionEndIsTheLastDayOfTheThirdYearAfter() {
        assertEquals(Instant.parse("2030-01-01T00:00:00Z"), ModerationLog.retentionEnd(Instant.parse("2026-10-17T12:00:00Z")))
        assertEquals(Instant.parse("2030-01-01T00:00:00Z"), ModerationLog.retentionEnd(Instant.parse("2026-01-01T00:00:00Z")))
        assertEquals(Instant.parse("2031-01-01T00:00:00Z"), ModerationLog.retentionEnd(Instant.parse("2027-12-31T23:59:59Z")))
    }

    /** The bite: dated from the appointment (2026) rather than the removal (2028), these would end in 2030, not 2032. */
    @Test
    fun aModeratorsEventsAreKeptFromTheirRemovalNotTheirAppointment() {
        val appointed = Instant.parse("2026-03-01T10:00:00Z")
        val removed = Instant.parse("2028-06-01T10:00:00Z")
        QuarkusTransaction.requiringNew().run {
            handler.enable(OWNER, KEY.kemPublicKey, KEY.signPublicKey, IDENTITY_PUBLIC_KEY, sealedGrant(), appointed)
            handler.appoint(OWNER, address, appointed)
            handler.accept(deputy, appointed)
        }
        assertEquals(listOf<Instant?>(null, null), expiries(deputy), "open while on the roster")
        QuarkusTransaction.requiringNew().run { handler.remove(OWNER, deputy, removed) }
        assertEquals(List(3) { Instant.parse("2032-01-01T00:00:00Z") }, expiries(deputy))
        // The Server Owner is still on the roster: their events stay open.
        assertTrue(expiries(OWNER).all { it == null })
    }

    @Test
    fun anEventAboutAnAccountOffTheRosterIsDatedAtOnce() {
        val at = Instant.parse("2027-02-02T00:00:00Z")
        QuarkusTransaction.requiringNew().run { log.append(ModerationEvent.EPOCH_MINTED, at, deputy, deputy, epoch = 9, reason = "BY_HAND") }
        assertEquals(listOf<Instant?>(Instant.parse("2031-01-01T00:00:00Z")), expiries(deputy))
    }

    @Test
    fun thePurgeDeletesWhatExpiredAndKeepsTheRest() {
        QuarkusTransaction.requiringNew().run {
            log.append(ModerationEvent.APPOINTED, Instant.parse("2020-05-05T00:00:00Z"), deputy, OWNER) // ends 2024
            log.append(ModerationEvent.APPOINTED, Instant.now(), deputy, OWNER) // ends in four years
        }
        purge.sweep(Instant.now())
        assertEquals(1, expiries(deputy).size)
    }

    @Test
    fun theLogIsNeverInALogLine() {
        val canary = "c2VhbGVkLWNhbmFyeS0${UUID.randomUUID().toString().replace("-", "")}"
        val records = mutableListOf<LogRecord>()
        val root = Logger.getLogger("")
        val capture = object : Handler() {
            override fun publish(record: LogRecord) { records.add(record) }
            override fun flush() {}
            override fun close() {}
        }.apply { level = Level.ALL }
        // Every record the app emits at its configured levels (the root's, as the test profile sets it — what a
        // deployment logs); not Level.ALL on the root, which would turn on Hibernate's DEBUG dump of every flushed entity,
        // a level no deployment runs at.
        root.addHandler(capture)
        try {
            val key = epochKey()
            enable(OWNER, key, IDENTITY_PUBLIC_KEY)
            appointAndAccept(OWNER, address, deputy)
            asUser(OWNER).contentType(ContentType.JSON).headers(proof(OWNER, "putModerationDelivery", key))
                .body(mapOf("identityPublicKey" to IDENTITY_PUBLIC_KEY, "grants" to listOf(mapOf("epoch" to 1, "sealedGrant" to sealedGrant()))))
                .put("/api/moderation/deliveries/$deputy").then().statusCode(204)
            asOwner(OWNER).delete("/api/instance/moderation/moderators/$deputy").then().statusCode(200)
            QuarkusTransaction.requiringNew().run {
                log.append(ModerationEvent.EPOCH_MINTED, Instant.now(), OWNER, OWNER, epoch = 2, reason = "BY_HAND", sealedText = canary)
            }
            // The capture's own control: a record the app's logging emits at INFO arrives here.
            Logger.getLogger("me.raddatz.veyq.core.moderation").info(CONTROL)
        } finally {
            root.removeHandler(capture)
        }
        // The events were written (the control), and none of their values is in a captured record.
        assertEquals(1L, QuarkusTransaction.requiringNew().call { ModerationEventEntity.count("sealedText", canary) })
        assertTrue(expiries(deputy).size >= 4)
        assertTrue(records.any { it.message == CONTROL }, "the capture missed the control record — it is not attached where the logs go")
        val text = records.joinToString("\n") { "${it.message} ${it.parameters?.joinToString() ?: ""} ${it.thrown ?: ""}" }
        assertFalse(text.contains(canary), "a sealed text reached a log record")
        assertFalse(text.contains(deputy), "a moderator's account reached a log record")
        assertFalse(text.contains(address), "a moderator's address reached a log record")
    }

    private fun expiries(moderator: String): List<Instant?> = QuarkusTransaction.requiringNew().call {
        ModerationEventEntity.list("moderatorId = ?1 order by occurredAt", moderator).map { it.expiresAt }
    }

    private companion object {
        const val OWNER = "1d1e1f20-2122-4324-8526-272829202a2b"
        const val CONTROL = "moderation-log-capture-control"
        val KEY = epochKey()
    }
}
