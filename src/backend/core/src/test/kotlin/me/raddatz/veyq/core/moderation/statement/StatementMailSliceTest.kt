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
import me.raddatz.veyq.core.testing.TestFixtures.seedAttachment
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.seedScopeMember
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

    /**
     * Inbox #821: a blocked link's statement is worded by each recipient's relation to it, which its recipient row records —
     * the link's creator reads "a link you created", a member whose upload the link reaches "a link to something you
     * uploaded", never the creator's words.
     */
    @Test
    fun aBlockedLinksMailIsWordedByEachRecipientsRelationToIt() {
        val m = seedMemberScope(ScopeEntity.GROUP)
        val uploader = seedScopeMember(m.scopeId, grantedEpoch = 1)
        val folder = seedNode(m.scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        seedAttachment(m.scopeId, folder, "COVER", uploaderUserId = uploader)
        asMember(m.userId, m.scopeId, body(listOf(item("NODE", folder)))).then().statusCode(201)
        mailbox.waitForIncomingEmail(500, 1)
        mailbox.purgeEmailFromAllMailboxes()
        val caseId = openCase("NODE", folder)!!.id
        mod.take(caseId, "SHARE", shareId = share(m.userId, folder)).then().statusCode(201)
        mailbox.waitForIncomingEmail(2_000, 2)

        val roles = QuarkusTransaction.requiringNew().call {
            StatementRecipientEntity.list("recipientId in ?1", listOf(m.userId, uploader)).associate { it.recipientId to it.roleSet }
        }
        assertEquals(mapOf(m.userId to listOf(StatementRole.SHARE_CREATOR), uploader to listOf(StatementRole.UPLOADER)), roles)
        val creatorText = textOf(mailsTo(addressOf(m.userId)).single())
        assertTrue(creatorText.contains("took a measure: a link you created was blocked."), creatorText)
        assertTrue(creatorText.contains("ein von dir erstellter Link wurde gesperrt."), creatorText)
        val uploaderText = textOf(mailsTo(addressOf(uploader)).single())
        assertTrue(uploaderText.contains("took a measure: a link to something you uploaded was blocked."), uploaderText)
        assertTrue(uploaderText.contains("ein Link zu etwas, das du hochgeladen hast, wurde gesperrt."), uploaderText)
        assertFalse(uploaderText.contains("you created"), uploaderText)
        assertFalse(uploaderText.contains("von dir erstellter"), uploaderText)
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

    /**
     * A reporter's decision carries their whole redress on every instance (DSA Art. 16(5), 53; ADR 0093 §§ 7, 14): report
     * again, the contact — the gap where none is set —, the courts, and the coordinator under its own "where the DSA
     * applies". Control on the same mail: a contact set replaces its gap.
     */
    @Test
    fun aReportersDecisionCarriesTheirWholeRedressAndTheGapWhereNoContactIsSet() {
        val before = QuarkusTransaction.requiringNew().call { OperatorDetailsEntity.findById(OperatorDetailsEntity.SINGLETON_ID)?.contactEmail }
        try {
            contact(null)
            val reporter = "redress-${UUID.randomUUID()}@example.com"
            val r1 = reported()
            mod.dismiss(r1.caseId, reporters = listOf(r1.reportId to reporter)).then().statusCode(200)
            val mail = textOf(mailsTo(reporter).single { it.subject == StatementMail.DECISION_SUBJECT })
            assertTrue(mail.contains("If you have more information, you can report it again."), mail)
            assertTrue(mail.contains("Questions go to not stated by this operator."), mail)
            assertTrue(mail.contains("You can also go to court."), mail)
            assertTrue(mail.contains("Where the Digital Services Act (DSA) applies to this instance, you can also complain to the Digital Services Coordinator"), mail)
            assertTrue(mail.contains("Soweit das Gesetz über digitale Dienste (DSA) für diese Instanz gilt"), mail)

            contact("abuse@example.org")
            mailbox.purgeEmailFromAllMailboxes()
            val second = "redress-${UUID.randomUUID()}@example.com"
            val r2 = reported()
            mod.dismiss(r2.caseId, reporters = listOf(r2.reportId to second)).then().statusCode(200)
            val withContact = textOf(mailsTo(second).single { it.subject == StatementMail.DECISION_SUBJECT })
            assertTrue(withContact.contains("Questions go to abuse@example.org."), withContact)
            assertFalse(withContact.contains("not stated by this operator"), withContact)
        } finally {
            contact(before)
        }
    }

    /** The instance contact's column alone — the redress names it. */
    private fun contact(value: String?) = QuarkusTransaction.requiringNew().run { operator.lockedRow().contactEmail = value }

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
