package me.raddatz.veyq.core.moderation.report

import me.raddatz.veyq.core.admin.instance.InstanceMailSettingsEntity
import me.raddatz.veyq.core.admin.instance.InstanceMailSettingsHandler
import me.raddatz.veyq.core.admin.instance.InstanceMailUpdateOutcome
import me.raddatz.veyq.core.admin.operator.OperatorDetailsEntity
import me.raddatz.veyq.core.admin.operator.OperatorDetailsStore
import me.raddatz.veyq.core.moderation.ModerationTestKit
import me.raddatz.veyq.core.moderation.ModerationTestKit.appointAndAccept
import me.raddatz.veyq.core.moderation.ModerationTestKit.asUser
import me.raddatz.veyq.core.moderation.ModerationTestKit.enable
import me.raddatz.veyq.core.moderation.ModerationTestKit.epochKey
import me.raddatz.veyq.core.moderation.hold.HoldRowEntity
import me.raddatz.veyq.core.moderation.report.ReportTestKit.asMember
import me.raddatz.veyq.core.moderation.report.ReportTestKit.body
import me.raddatz.veyq.core.moderation.report.ReportTestKit.item
import me.raddatz.veyq.core.moderation.report.ReportTestKit.openCase
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.IDENTITY_PUBLIC_KEY
import me.raddatz.veyq.core.testing.TestFixtures.ensureUser
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.mail.Part
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The mails a report sends (uc.moderation.report; ADR 0093 § 2, ADR 0099 § 7, owner decisions Q6 and Q7), over GreenMail
 * on this class's own port: the new-case mail to every confirmed moderator and the instance contact's confirmed
 * notification address — the category and "new case" only, no id and no link — at once for the urgent three, in at most
 * one bundle a day for the rest; and the reporter's receipt, mailed once and stored nowhere.
 */
@QuarkusTest
class ReportMailSliceTest {

    @Inject
    lateinit var settings: InstanceMailSettingsHandler

    @Inject
    lateinit var notices: CaseNotices

    @Inject
    lateinit var operator: OperatorDetailsStore

    private lateinit var mailbox: GreenMail
    private lateinit var deputy: String
    private lateinit var deputyAddress: String

    @BeforeEach
    fun seed() {
        ModerationTestKit.reset()
        QuarkusTransaction.requiringNew().run {
            InstanceMailSettingsEntity.deleteAll()
            OperatorDetailsEntity.deleteAll()
            // Cases other classes left owe a notice too, and this class reads every mail: none of theirs is due here.
            ModerationCaseEntity.update("noticeDue = null, noticeKind = null, noticedAt = null")
        }
        ensureUser(OWNER, OWNER_EMAIL, "SERVER_OWNER")
        deputy = UUID.randomUUID().toString()
        deputyAddress = "report-deputy-$deputy@example.com"
        seedUser(id = deputy, email = deputyAddress)
        mailbox = GreenMail(ServerSetup(PORT, "127.0.0.1", "smtp")).also { it.start() }
        val saved = settings.update(OWNER, "127.0.0.1", PORT, "veyq@example.com", null, null, tls = false)
        assertTrue(saved is InstanceMailUpdateOutcome.Ok, "$saved")
        enable(OWNER, epochKey(), IDENTITY_PUBLIC_KEY)
        appointAndAccept(OWNER, deputyAddress, deputy)
        asUser(deputy).contentType(ContentType.JSON).body(mapOf("code" to codeIn(mailsTo(deputyAddress).single())))
            .post("/api/moderation/appointment/confirmation").then().statusCode(200)
        mailbox.purgeEmailFromAllMailboxes()
    }

    @AfterEach
    fun stop() {
        mailbox.stop()
        QuarkusTransaction.requiringNew().run {
            InstanceMailSettingsEntity.deleteAll()
            OperatorDetailsEntity.deleteAll()
        }
    }

    @Test
    fun anUrgentReportMailsEveryConfirmedModeratorAtOnceWithTheCategoryAndNoIdOrLink() {
        val m = seedMemberScope(ScopeEntity.GROUP)
        val folder = seedNode(m.scopeId, "FOLDER", wrappedContentKey = CONTENT_KEY)
        val reportId = asMember(m.userId, m.scopeId, body(listOf(item("NODE", folder)), category = "THREAT_TO_LIFE"))
            .then().statusCode(201).extract().path<String>("reportId")
        val case = openCase("NODE", folder)!!

        val mail = mailsTo(deputyAddress).single { it.subject == CaseNotices.URGENT_SUBJECT }
        val text = mail.subject + "\n" + textOf(mail)
        assertTrue(text.contains("1 × a threat to life or safety"), text)
        for (secret in listOf(case.id, reportId, folder, m.scopeId, m.userId)) {
            assertFalse(text.contains(secret), "the mail names an id: $secret")
        }
        assertFalse(Regex("https?://|www\\.").containsMatchIn(text), "the mail carries a link:\n$text")
        assertEquals(listOf(deputyAddress), mailbox.receivedMessages.flatMap { m2 -> m2.allRecipients.map { it.toString() } }, "the confirmed moderator alone")
        assertEquals(null, openCase("NODE", folder)!!.noticeDue, "mailed once")
    }

    @Test
    fun aNonUrgentReportWaitsForTheDaysBundleAndABundleGoesAtMostOnceADay() {
        val m = seedMemberScope(ScopeEntity.GROUP)
        val one = seedNode(m.scopeId, "FOLDER", wrappedContentKey = CONTENT_KEY)
        val two = seedNode(m.scopeId, "FOLDER", wrappedContentKey = CONTENT_KEY)
        asMember(m.userId, m.scopeId, body(listOf(item("NODE", one), item("NODE", two)), category = "TERMS_BREACH")).then().statusCode(201)
        mailbox.waitForIncomingEmail(1_000, 1)
        assertEquals(0, mailbox.receivedMessages.size, "not at once")

        val now = Instant.now()
        assertEquals(2, notices.mailBundle(now))
        val bundle = mailsTo(deputyAddress).single { it.subject == CaseNotices.BUNDLE_SUBJECT }
        assertTrue(textOf(bundle).contains("2 × a breach of the terms"), textOf(bundle))

        // A further case the same day waits for the next day's bundle.
        val three = seedNode(m.scopeId, "FOLDER", wrappedContentKey = CONTENT_KEY)
        asMember(m.userId, m.scopeId, body(listOf(item("NODE", three)), category = "ILLEGAL_CONTENT")).then().statusCode(201)
        assertEquals(0, notices.mailBundle(now.plus(Duration.ofHours(23))))
        assertEquals(1, notices.mailBundle(now.plus(Duration.ofHours(25))))
        mailbox.waitForIncomingEmail(2_000, 2)
        assertEquals(2, mailbox.receivedMessages.count { it.subject == CaseNotices.BUNDLE_SUBJECT })
    }

    @Test
    fun theInstanceContactsConfirmedNotificationAddressIsMailedTooAndAnUnconfirmedOneIsNot() {
        val contact = "report-contact-${UUID.randomUUID()}@example.com"
        QuarkusTransaction.requiringNew().run {
            operator.lockedRow().apply { notificationEmail = contact }
        }
        val m = seedMemberScope(ScopeEntity.GROUP)
        asMember(m.userId, m.scopeId, body(listOf(item("NODE", seedNode(m.scopeId, "FOLDER", wrappedContentKey = CONTENT_KEY))), category = "CHILD_SEXUAL_ABUSE"))
            .then().statusCode(201)
        mailbox.waitForIncomingEmail(2_000, 1)
        assertTrue(mailsTo(contact).isEmpty(), "an unconfirmed notification address is mailed nothing")

        QuarkusTransaction.requiringNew().run {
            operator.lockedRow().apply { confirmedAddress = contact; confirmedAt = Instant.now() }
        }
        asMember(m.userId, m.scopeId, body(listOf(item("NODE", seedNode(m.scopeId, "FOLDER", wrappedContentKey = CONTENT_KEY))), category = "TERRORIST_CONTENT"))
            .then().statusCode(201)
        mailbox.waitForIncomingEmail(2_000, 3)
        assertEquals(1, mailsTo(contact).count { it.subject == CaseNotices.URGENT_SUBJECT })
    }

    @Test
    fun theReporterGetsAReceiptOncePerReportBoundedPerAddressAndTheAddressIsStoredNowhere() {
        val reporter = "Report-Reporter-${UUID.randomUUID()}@Example.com"
        val m = seedMemberScope(ScopeEntity.GROUP)
        val reportIds = (1..4).map {
            val node = seedNode(m.scopeId, "FOLDER", wrappedContentKey = CONTENT_KEY)
            asMember(m.userId, m.scopeId, body(listOf(item("NODE", node)), category = "TERMS_BREACH", receiptAddress = reporter))
                .then().statusCode(201).extract().path<String>("reportId")
        }
        mailbox.waitForIncomingEmail(3_000, 3)
        val receipts = mailsTo(reporter).filter { it.subject == ReportReceived.RECEIPT_SUBJECT }
        assertEquals(3, receipts.size, "at most three receipts an address an hour (veyq.moderation.report.receipts-per-address)")
        assertTrue(textOf(receipts.first()).contains("Reference: ${ReportReceived.reference(reportIds.first())}"), textOf(receipts.first()))

        // Each receipt that went out is a "receipt sent" event on its report's case — the proof that stays once the
        // sealed contact went (ADR 0093 § 11); the one the bound held back is none.
        QuarkusTransaction.requiringNew().run {
            val sent = reportIds.map { id -> me.raddatz.veyq.core.moderation.ModerationEventEntity.count("event = ?1 and targetId = ?2", "RECEIPT_SENT", id) }
            assertEquals(listOf(1L, 1L, 1L, 0L), sent)
        }

        QuarkusTransaction.requiringNew().run {
            val stored = ModerationReportEntity.list("reporterAccount", m.userId).flatMap {
                listOf(it.sealedReport, it.reporterShare ?: "", it.category)
            } + HoldRowEntity.listAll().map { it.payload }
            assertTrue(stored.none { it.contains(reporter, ignoreCase = true) }, "the address is stored somewhere")
        }
    }

    private fun mailsTo(to: String): List<MimeMessage> {
        mailbox.waitForIncomingEmail(2_000, 1)
        return mailbox.receivedMessages.filter { m -> m.allRecipients.any { it.toString().equals(to, ignoreCase = true) } }
    }

    private fun textOf(mail: MimeMessage): String = partsOf(mail).first { it.isMimeType("text/plain") }.content as String

    private fun codeIn(mail: MimeMessage): String = CODE.find(textOf(mail))?.groupValues?.get(1) ?: error("no code in:\n${textOf(mail)}")

    private fun partsOf(mail: Part): List<Part> = when (val content = mail.content) {
        is MimeMultipart -> (0 until content.count).flatMap { partsOf(content.getBodyPart(it)) }
        else -> listOf(mail)
    }

    private companion object {
        const val OWNER = "3d2e2f30-3132-4334-8536-373839303a3c"
        const val OWNER_EMAIL = "report-mail-owner@example.com"
        const val PORT = 13971
        val CODE = Regex("""(?m)^\s+([0-9]{8})\s*$""")
    }
}
