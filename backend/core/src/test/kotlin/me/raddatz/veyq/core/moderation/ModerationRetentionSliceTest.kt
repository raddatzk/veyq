package me.raddatz.veyq.core.moderation

import me.raddatz.veyq.core.moderation.block.ModerationBlockEntity
import me.raddatz.veyq.core.moderation.block.ModerationProposalEntity
import me.raddatz.veyq.core.moderation.block.ProposalOutcome
import me.raddatz.veyq.core.moderation.block.ProposalStep
import me.raddatz.veyq.core.moderation.cases.CaseDecisions
import me.raddatz.veyq.core.moderation.cases.CaseTestKit
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.decideObjection
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.dismiss
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.orderBody
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.order
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.take
import me.raddatz.veyq.core.moderation.cases.DecisionOutcome
import me.raddatz.veyq.core.moderation.order.AuthorityOrderEntity
import me.raddatz.veyq.core.moderation.report.ModerationCaseEntity
import me.raddatz.veyq.core.moderation.report.ModerationReportEntity
import me.raddatz.veyq.core.moderation.report.ReportShareEntity
import me.raddatz.veyq.core.moderation.report.ReportTestKit.SEALED_CONTACT
import me.raddatz.veyq.core.moderation.report.ReportTestKit.asMember
import me.raddatz.veyq.core.moderation.report.ReportTestKit.body
import me.raddatz.veyq.core.moderation.report.ReportTestKit.item
import me.raddatz.veyq.core.moderation.report.ReportTestKit.openCase
import me.raddatz.veyq.core.moderation.statement.ModerationObjectionEntity
import me.raddatz.veyq.core.moderation.statement.ModerationStatementEntity
import me.raddatz.veyq.core.moderation.statement.StatementDeferralEntity
import me.raddatz.veyq.core.moderation.statement.StatementRecipientEntity
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.sharing.GuestTreeShare
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.TestIds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * How long moderation keeps a case's records (ADR 0093 § 11; `ModerationRecordsPurge`, run by [ModerationPurge]): a
 * closed case goes whole — every table that names it, its report's sealed part and shares, its log — at 31 December of the
 * third year after it closed, not a day sooner; never while a measure of it is in force (its lift dates it again); a report
 * spanning two cases goes with the last; an order that opened no case goes with its event; and a reporter's contact goes
 * alone six months after the decision was told (or, told nothing, made), never while a case of the report is open. The
 * clock is the purge's argument.
 */
@QuarkusTest
class ModerationRetentionSliceTest {

    @Inject
    lateinit var purge: ModerationPurge

    @Inject
    lateinit var decisions: CaseDecisions

    @Inject
    lateinit var log: ModerationLog

    private data class Reported(val userId: String, val scopeId: String, val folder: String, val caseId: String)

    private fun reported(contact: String? = null): Reported {
        val m = seedMemberScope(ScopeEntity.GROUP)
        val folder = seedNode(m.scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        asMember(m.userId, m.scopeId, body(listOf(item("NODE", folder)), sealedContact = contact)).then().statusCode(201)
        return Reported(m.userId, m.scopeId, folder, openCase("NODE", folder)!!.id)
    }

    private fun <T> tx(block: () -> T): T = QuarkusTransaction.requiringNew().call(block)

    private fun reportOf(caseId: String): ModerationReportEntity? = tx {
        ReportShareEntity.find("caseId", caseId).firstResult()?.let { ModerationReportEntity.findById(it.reportId) }
    }

    /** What every table still holds of [caseId]: table name → rows. */
    private fun rowsOf(caseId: String, reportId: String): Map<String, Long> = tx {
        val statements = ModerationStatementEntity.list("caseId", caseId).map { it.id }
        mapOf(
            "case" to ModerationCaseEntity.count("id", caseId),
            "report" to ModerationReportEntity.count("id", reportId),
            "report_share" to ReportShareEntity.count("caseId", caseId),
            "block" to ModerationBlockEntity.count("caseId", caseId),
            "proposal" to ModerationProposalEntity.count("caseId", caseId),
            "statement" to statements.size.toLong(),
            "recipient" to if (statements.isEmpty()) 0L else StatementRecipientEntity.count("statementId in ?1", statements),
            "deferral" to StatementDeferralEntity.count("caseId", caseId),
            "objection" to ModerationObjectionEntity.count("caseId", caseId),
            "event" to ModerationEventEntity.count("caseId", caseId),
        )
    }

    @Test
    fun aClosedCaseGoesWholeOnItsLogsDateAndNotADaySooner() {
        val mod = CaseTestKit.moderator()
        val r = reported(contact = SEALED_CONTACT)
        val reportId = reportOf(r.caseId)!!.id
        // A measure with its statement to the space's owner, who objects; the only moderator lifts it on the objection.
        val statementId = mod.take(r.caseId, "CONTENT", targetId = r.folder).then().statusCode(201)
            .extract().path<String>("measures[0].statement.id")
        ModerationTestKit.asUser(r.userId).contentType(ContentType.JSON)
            .body("""{"epoch":1,"sealedObjection":"c2VhbGVkLW9iamVjdGlvbg=="}""").post("/api/statements/$statementId/objection")
            .then().statusCode(201)
        val objectionId = tx { ModerationObjectionEntity.find("caseId", r.caseId).firstResult()!!.id }
        // A deferral and a declined proposal on it too (their own flows are AuthorityOrdersSliceTest's and the admin's).
        tx {
            StatementDeferralEntity().apply {
                id = UUID.randomUUID().toString(); this.statementId = statementId; caseId = r.caseId; epoch = 1
                sealedReason = CaseTestKit.SEALED_NOTE; enteredBy = mod.id
            }.persist()
            ModerationProposalEntity().apply {
                id = UUID.randomUUID().toString(); caseId = r.caseId; step = ProposalStep.MEMBER_SIGN_IN.name; targetId = r.userId
                proposedBy = mod.id; proposedAt = Instant.now(); outcome = ProposalOutcome.DECLINED.name; settledAt = Instant.now()
                settledBy = mod.id
            }.persist()
        }
        mod.decideObjection(r.caseId, objectionId, "LIFT").then().statusCode(200)
        val closed = CaseTestKit.caseRow(r.caseId)
        assertEquals("CLOSED", closed.state)
        val end = ModerationLog.retentionEnd(closed.closedAt!!)
        rowsOf(r.caseId, reportId).forEach { (table, n) -> assert(n > 0) { "$table has no row to purge — the test proves nothing" } }

        purge.sweep(end.minus(1, ChronoUnit.DAYS))
        rowsOf(r.caseId, reportId).forEach { (table, n) -> assert(n > 0) { "$table went before 31 December of the third year" } }
        purge.sweep(end.plus(1, ChronoUnit.MINUTES))
        rowsOf(r.caseId, reportId).forEach { (table, n) -> assertEquals(0L, n, "$table outlived its case") }
    }

    @Test
    fun aMeasureInForceKeepsItsCaseAndItsLiftDatesItAgain() {
        val mod = CaseTestKit.moderator()
        val r = reported()
        val reportId = reportOf(r.caseId)!!.id
        val measureId = mod.take(r.caseId, "CONTENT", targetId = r.folder).then().statusCode(201).extract().path<String>("measures[0].id")
        // The measure's hold ends after six months; the case closes with the block still in force.
        purge.sweep(Instant.now().plus(200, ChronoUnit.DAYS))
        val closed = CaseTestKit.caseRow(r.caseId)
        assertEquals("CLOSED", closed.state)
        val pastDate = ModerationLog.retentionEnd(closed.closedAt!!).plus(30, ChronoUnit.DAYS)
        purge.sweep(pastDate)
        assertEquals(1L, rowsOf(r.caseId, reportId)["case"], "the purge deleted a case whose block is in force")
        assertEquals(1L, tx { ModerationBlockEntity.count("id = ?1 and activeKey is not null", measureId) }, "the block was lifted by a purge")
        assert(rowsOf(r.caseId, reportId).getValue("event") > 0) { "the log's own purge took a kept case's events" }

        // Lifted then: the lift is an event of its own date, and the case goes on that date — not before.
        assertEquals(DecisionOutcome.Done, decisions.lift(mod.id, r.caseId, measureId, null, now = pastDate))
        val lifted = ModerationLog.retentionEnd(pastDate)
        purge.sweep(lifted.minus(1, ChronoUnit.DAYS))
        assertEquals(1L, rowsOf(r.caseId, reportId)["case"])
        purge.sweep(lifted.plus(1, ChronoUnit.MINUTES))
        rowsOf(r.caseId, reportId).forEach { (table, n) -> assertEquals(0L, n, "$table outlived its case") }
    }

    @Test
    fun aReportOfTwoItemsGoesWithItsLastCase() {
        val mod = CaseTestKit.moderator()
        val m = seedMemberScope(ScopeEntity.GROUP)
        val a = seedNode(m.scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val b = seedNode(m.scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        asMember(m.userId, m.scopeId, body(listOf(item("NODE", a), item("NODE", b)))).then().statusCode(201)
        val first = openCase("NODE", a)!!.id
        val second = openCase("NODE", b)!!.id
        val reportId = reportOf(first)!!.id
        mod.dismiss(first).then().statusCode(200)
        val end = ModerationLog.retentionEnd(CaseTestKit.caseRow(first).closedAt!!)
        purge.sweep(end.plus(1, ChronoUnit.MINUTES))
        assertNull(tx { ModerationCaseEntity.findById(first) })
        assertEquals(0L, tx { ReportShareEntity.count("caseId", first) })
        assertNotNull(tx { ModerationReportEntity.findById(reportId) }, "the report went while another case still names it")
        assertEquals("OPEN", CaseTestKit.caseRow(second).state)
        // The second case decided and closed: the report goes with it.
        val later = end.plus(2, ChronoUnit.MINUTES)
        assertEquals(DecisionOutcome.Done, tx { decisions.dismissIn(mod.id, second, null, later) })
        purge.sweep(ModerationLog.retentionEnd(later).plus(1, ChronoUnit.MINUTES))
        assertNull(tx { ModerationCaseEntity.findById(second) })
        assertNull(tx { ModerationReportEntity.findById(reportId) }, "the report outlived its last case")
    }

    @Test
    fun anOrderThatFoundNoShareGoesWithItsEventAndAnOrdersCaseWithItsOrder() {
        val mod = CaseTestKit.moderator()
        val unknown = UUID.randomUUID().toString()
        mod.order(orderBody(TestIds.shareId(), orderId = unknown)).then().statusCode(201)
        val entered = tx { AuthorityOrderEntity.findById(unknown)!!.enteredAt }
        val end = ModerationLog.retentionEnd(entered)
        // The one who entered it is on the roster, yet its event is dated from the order, like the order's row.
        assertEquals(end, tx { ModerationEventEntity.find("targetId", unknown).firstResult()!!.expiresAt })

        // An order on a known share opens a case whose hold the order sets; it closes when that ends.
        val m = seedMemberScope(ScopeEntity.GROUP)
        val root = seedNode(m.scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val share = GuestTreeShare.seed(m.userId, m.scopeId, root, ShareEntity.READ).shareId
        val caseId = mod.order(orderBody(share, kind = "PRESERVATION")).then().statusCode(201).extract().path<String>("caseId")
        val orderId = tx { AuthorityOrderEntity.find("caseId", caseId).firstResult()!!.id }
        purge.sweep(Instant.now().plus(61, ChronoUnit.DAYS))
        assertEquals("CLOSED", CaseTestKit.caseRow(caseId).state)
        val caseEnd = ModerationLog.retentionEnd(CaseTestKit.caseRow(caseId).closedAt!!)

        purge.sweep(minOf(end, caseEnd).minus(1, ChronoUnit.DAYS))
        assertNotNull(tx { AuthorityOrderEntity.findById(unknown) })
        assertNotNull(tx { AuthorityOrderEntity.findById(orderId) })
        purge.sweep(maxOf(end, caseEnd).plus(1, ChronoUnit.MINUTES))
        assertNull(tx { AuthorityOrderEntity.findById(unknown) }, "an order that opened no case outlived its date")
        assertEquals(0L, tx { ModerationEventEntity.count("targetId", unknown) }, "its event outlived it")
        assertNull(tx { AuthorityOrderEntity.findById(orderId) }, "an order outlived its case")
        assertNull(tx { ModerationCaseEntity.findById(caseId) })
    }

    @Test
    fun aReportersContactGoesSixMonthsAfterTheDecisionWasToldAndNeverWhileACaseIsOpen() {
        val mod = CaseTestKit.moderator()
        val told = reported(contact = SEALED_CONTACT)
        val untold = reported(contact = SEALED_CONTACT)
        val open = reported(contact = SEALED_CONTACT)
        mod.dismiss(told.caseId).then().statusCode(200)
        mod.dismiss(untold.caseId).then().statusCode(200)
        val decided = CaseTestKit.caseRow(untold.caseId).closedAt!!
        // The decision told to the first reporter a month after it was made (the mail itself is StatementMailSliceTest's).
        val toldAt = decided.plus(30, ChronoUnit.DAYS)
        val reportId = reportOf(told.caseId)!!.id
        tx { log.appendCase(ModerationEvent.DECISION_SENT, toldAt, told.caseId, reason = "DISMISSED", targetId = reportId) }
        val sixMonths = ModerationRecordsPurge.CONTACT

        purge.sweep(decided.plus(sixMonths).minus(1, ChronoUnit.DAYS))
        listOf(told, untold, open).forEach { assertNotNull(reportOf(it.caseId)!!.sealedContact, "a contact went before its six months") }
        // Six months after the decision: the untold one goes (nothing more to tell), the told one waits for its own date.
        purge.sweep(decided.plus(sixMonths).plus(1, ChronoUnit.HOURS))
        assertNull(reportOf(untold.caseId)!!.sealedContact, "a contact outlived the decision nobody could be told")
        assertNotNull(reportOf(told.caseId)!!.sealedContact)
        purge.sweep(toldAt.plus(sixMonths).plus(1, ChronoUnit.HOURS))
        val kept = reportOf(told.caseId)!!
        assertNull(kept.sealedContact, "a contact outlived six months after the decision was told")
        assertEquals("AXNlYWxlZC1yZXBvcnQ=", kept.sealedReport, "the report's sealed part went with the contact")
        assertEquals(1L, tx { ModerationEventEntity.count("event = ?1 and targetId = ?2", "DECISION_SENT", reportId) }, "the proof went")
        // A case still open keeps its reporter's contact: the decision is still owed to them.
        assertNotNull(reportOf(open.caseId)!!.sealedContact, "a contact went while its case was open")
    }
}
