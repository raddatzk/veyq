package me.raddatz.veyq.core.moderation.statement

import me.raddatz.veyq.core.admin.instance.InstanceMailSettingsEntity
import me.raddatz.veyq.core.admin.instance.InstanceMailSettingsHandler
import me.raddatz.veyq.core.admin.instance.InstanceMailUpdateOutcome
import me.raddatz.veyq.core.admin.operator.OperatorDetailsEntity
import me.raddatz.veyq.core.moderation.ModerationEventEntity
import me.raddatz.veyq.core.moderation.ModerationTestKit.asUser
import me.raddatz.veyq.core.moderation.cases.CaseTestKit
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.SEALED_FACTS
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.SEALED_NOTE
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.case
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.decideObjection
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.dismiss
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.take
import me.raddatz.veyq.core.moderation.report.ReportReceived
import me.raddatz.veyq.core.moderation.report.ReportTestKit.asMember
import me.raddatz.veyq.core.moderation.report.ReportTestKit.body
import me.raddatz.veyq.core.moderation.report.ReportTestKit.item
import me.raddatz.veyq.core.moderation.report.ReportTestKit.openCase
import me.raddatz.veyq.core.moderation.report.ReportTestKit.share
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.tenant.TenantEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The statement's mails (uc.moderation.object-to-a-measure steps 5, 7, 8, 10; ADR 0097 decision 3 as owner decision Q11
 * widened it; ADR 0049), over GreenMail on this class's own port: the affected member is told a statement is in veyq —
 * the template's clear parts, never the facts — and a send that did not go out is retried; a reporter is told the
 * decision once, from an address the server stores nowhere; the moderators learn of an objection, the objector of its
 * answer.
 */
@QuarkusTest
class StatementMailSliceTest {

    @Inject
    lateinit var settings: InstanceMailSettingsHandler

    @Inject
    lateinit var mail: StatementMail

    @Inject
    lateinit var operator: me.raddatz.veyq.core.admin.operator.OperatorDetailsStore

    private lateinit var mailbox: GreenMail
    private lateinit var mod: CaseTestKit.Moderator

    private data class Reported(val userId: String, val scopeId: String, val folder: String, val caseId: String, val reportId: String)

    @BeforeEach
    fun seed() {
        mod = CaseTestKit.moderator()
        QuarkusTransaction.requiringNew().run { InstanceMailSettingsEntity.deleteAll() }
        mailbox = GreenMail(ServerSetup(PORT, "127.0.0.1", "smtp")).also { it.start() }
        configureMail()
    }

    @AfterEach
    fun stop() {
        mailbox.stop()
        QuarkusTransaction.requiringNew().run { InstanceMailSettingsEntity.deleteAll() }
    }

    private fun configureMail() {
        val saved = settings.update(mod.id, "127.0.0.1", PORT, "veyq@example.com", null, null, tls = false)
        assertTrue(saved is InstanceMailUpdateOutcome.Ok, "$saved")
        mailbox.waitForIncomingEmail(1_000, 1)
        mailbox.purgeEmailFromAllMailboxes()
    }

    private fun reported(receipt: String? = null): Reported {
        val m = seedMemberScope(ScopeEntity.GROUP)
        val folder = seedNode(m.scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val reportId = asMember(m.userId, m.scopeId, body(listOf(item("NODE", folder)), receiptAddress = receipt)).then().statusCode(201)
            .extract().path<String>("reportId")
        mailbox.waitForIncomingEmail(500, 1)
        mailbox.purgeEmailFromAllMailboxes()
        return Reported(m.userId, m.scopeId, folder, openCase("NODE", folder)!!.id, reportId)
    }

    private fun addressOf(userId: String) = "u-$userId@example.com"

    @Test
    fun theAffectedMemberIsMailedThatTheStatementIsInVeyqAndTheMailCarriesNoFacts() {
        val r = reported()
        mod.take(r.caseId, "CONTENT", targetId = r.folder).then().statusCode(201)
        val sent = mailsTo(addressOf(r.userId)).single()
        val text = sent.subject + "\n" + textOf(sent)
        val domain = QuarkusTransaction.requiringNew().call {
            TenantEntity.findById(ScopeEntity.findById(r.scopeId)!!.tenantId)!!.domain
        }
        assertTrue(text.contains("content was blocked"), text)
        assertTrue(text.contains("You are told as an owner of the space it lies in."), text)
        assertTrue(text.contains("The ground: it breaches the terms of use that apply here."), text)
        assertTrue(text.contains("What they found is in veyq: sign in at $domain to read it."), text)
        assertTrue(text.contains("Inhalte wurden gesperrt"), text)
        // ADR 0049: the facts — sealed to the moderation key or to the member — and every id stay out of the mail.
        for (secret in listOf(SEALED_FACTS, SEALED_NOTE, r.caseId, r.folder, r.scopeId, r.userId, mod.id)) {
            assertFalse(text.contains(secret), "the mail carries $secret:\n$text")
        }
        assertNotNull(QuarkusTransaction.requiringNew().call { StatementRecipientEntity.find("recipientId", r.userId).firstResult()!!.mailedAt })
    }

    @Test
    fun aStatementMailThatDidNotGoOutIsRetriedOnce() {
        QuarkusTransaction.requiringNew().run { InstanceMailSettingsEntity.deleteAll() }
        val r = reported()
        mod.take(r.caseId, "CONTENT", targetId = r.folder).then().statusCode(201)
        assertNull(QuarkusTransaction.requiringNew().call { StatementRecipientEntity.find("recipientId", r.userId).firstResult()!!.mailedAt })
        configureMail()
        assertTrue(mail.retryDue() >= 1)
        assertEquals(1, mailsTo(addressOf(r.userId)).size)
        mail.retryDue()
        mailbox.waitForIncomingEmail(500, 2)
        assertEquals(1, mailbox.receivedMessages.count { m -> m.allRecipients.any { it.toString() == addressOf(r.userId) } }, "mailed once")
    }

    @Test
    fun aReporterIsToldTheDecisionOnceAndTheAddressIsStoredNowhere() {
        val reporter = "statement-reporter-${UUID.randomUUID()}@example.com"
        val r = reported()
        val other = reported()
        // A notice for a report of another case tells nobody; one of this case tells its reporter the measure.
        mod.take(r.caseId, "CONTENT", targetId = r.folder, reporters = listOf(other.reportId to reporter, r.reportId to reporter))
            .then().statusCode(201)
        val told = mailsTo(reporter).filter { it.subject == StatementMail.DECISION_SUBJECT }
        assertEquals(1, told.size)
        assertTrue(textOf(told.single()).contains("took a measure"), textOf(told.single()))
        assertTrue(textOf(told.single()).contains("Reference: ${ReportReceived.reference(r.reportId)}"), textOf(told.single()))
        val event = QuarkusTransaction.requiringNew().call { ModerationEventEntity.find("caseId = ?1 and event = ?2", r.caseId, "DECISION_SENT").list() }
        assertEquals(listOf(r.reportId), event.map { it.targetId })
        assertEquals(listOf("DECIDED"), event.map { it.reason })
        // A second decision on the case does not tell the same report again.
        mod.take(r.caseId, "SHARE", shareId = share(r.userId, r.folder), reporters = listOf(r.reportId to reporter)).then().statusCode(201)
        mailbox.waitForIncomingEmail(500, 3)
        assertEquals(1, mailbox.receivedMessages.count { it.subject == StatementMail.DECISION_SUBJECT })
        // A dismissal tells the other case's reporter that no measure was taken.
        mod.dismiss(other.caseId, reporters = listOf(other.reportId to reporter)).then().statusCode(200)
        val dismissed = mailsTo(reporter).filter { it.subject == StatementMail.DECISION_SUBJECT }.map { textOf(it) }
        assertTrue(dismissed.any { it.contains("took no measure") }, dismissed.toString())
        QuarkusTransaction.requiringNew().run {
            val stored = ModerationEventEntity.list("caseId in ?1", listOf(r.caseId, other.caseId)).map { listOf(it.reason, it.targetId, it.sealedText).joinToString() }
            assertTrue(stored.none { it.contains(reporter) }, "the address is stored")
        }
    }

    @Test
    fun aReportersDecisionCarriesTheirRedressInTheFormTheModeSets() {
        val before = QuarkusTransaction.requiringNew().call { OperatorDetailsEntity.findById(OperatorDetailsEntity.SINGLETON_ID)?.operatingMode }
        try {
            // Organisation: the full redress — report again, the courts, the Digital Services Coordinator (DSA Art. 16(5), 53).
            mode(null)
            val reporter = "redress-${UUID.randomUUID()}@example.com"
            val org = reported()
            mod.dismiss(org.caseId, reporters = listOf(org.reportId to reporter)).then().statusCode(200)
            val full = textOf(mailsTo(reporter).single { it.subject == StatementMail.DECISION_SUBJECT })
            assertTrue(full.contains("If you have more information, you can report it again."), full)
            assertTrue(full.contains("You can also go to court."), full)
            assertTrue(full.contains("Digital Services Coordinator of the Member State where you live or are established"), full)
            assertTrue(full.contains("Koordinator für digitale Dienste"), full)
            // Private: the reduced form drops the courts and the coordinator — and keeps "report again" (the control).
            mode("PRIVATE")
            mailbox.purgeEmailFromAllMailboxes()
            val privateReporter = "redress-${UUID.randomUUID()}@example.com"
            val priv = reported()
            mod.take(priv.caseId, "CONTENT", targetId = priv.folder, facts = false, reporters = listOf(priv.reportId to privateReporter))
                .then().statusCode(201)
            val reduced = textOf(mailsTo(privateReporter).single { it.subject == StatementMail.DECISION_SUBJECT })
            assertTrue(reduced.contains("If you have more information, you can report it again."), reduced)
            assertFalse(reduced.contains("go to court"), reduced)
            assertFalse(reduced.contains("Digital Services Coordinator"), reduced)
        } finally {
            mode(before)
        }
    }

    /** The operating mode's column alone — the statement's form reads it. */
    private fun mode(value: String?) = QuarkusTransaction.requiringNew().run { operator.lockedRow().operatingMode = value }

    @Test
    fun theModeratorsLearnOfAnObjectionAndTheObjectorOfItsAnswer() {
        // The Server Owner confirms their moderator address, as the moderation surface does after accepting.
        asUser(mod.id).post("/api/moderation/appointment/confirmation-code").then().statusCode(200)
        val ownerAddress = QuarkusTransaction.requiringNew().call { me.raddatz.veyq.core.identity.VeyqUser.findById(mod.id)!!.email }
        val code = CODE.find(textOf(mailsTo(ownerAddress).last()))!!.groupValues[1]
        asUser(mod.id).contentType(ContentType.JSON).body(mapOf("code" to code)).post("/api/moderation/appointment/confirmation").then().statusCode(200)
        mailbox.purgeEmailFromAllMailboxes()

        val r = reported()
        val statementId = mod.take(r.caseId, "CONTENT", targetId = r.folder).then().statusCode(201)
            .extract().path<String>("measures[0].statement.id")
        asUser(r.userId).contentType(ContentType.JSON).body("""{"epoch":1,"sealedObjection":"c2VhbGVkLW9iamVjdGlvbg=="}""")
            .post("/api/statements/$statementId/objection").then().statusCode(201)
        val notice = mailsTo(ownerAddress).single { it.subject == StatementMail.OBJECTION_SUBJECT }
        assertFalse(textOf(notice).contains("c2VhbGVkLW9iamVjdGlvbg=="), "the objection stays sealed")
        val objectionId = mod.case(r.caseId).then().extract().path<String>("objections[0].id")
        mod.decideObjection(r.caseId, objectionId, "UPHOLD").then().statusCode(200)
        assertEquals(1, mailsTo(addressOf(r.userId)).count { it.subject == StatementMail.ANSWER_SUBJECT })
    }

    private fun mailsTo(to: String): List<MimeMessage> {
        mailbox.waitForIncomingEmail(2_000, 1)
        return mailbox.receivedMessages.filter { m -> m.allRecipients.any { it.toString().equals(to, ignoreCase = true) } }
    }

    private fun textOf(mail: MimeMessage): String = partsOf(mail).first { it.isMimeType("text/plain") }.content as String

    private fun partsOf(mail: Part): List<Part> = when (val content = mail.content) {
        is MimeMultipart -> (0 until content.count).flatMap { partsOf(content.getBodyPart(it)) }
        else -> listOf(mail)
    }

    private companion object {
        const val PORT = 13972
        val CODE = Regex("""(?m)^\s+([0-9]{8})\s*$""")
    }
}
