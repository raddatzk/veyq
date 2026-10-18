package me.raddatz.veyq.core.moderation.cases

import me.raddatz.veyq.core.moderation.ModerationEventEntity
import me.raddatz.veyq.core.moderation.ModerationPurge
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.case
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.export
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.extend
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.handover
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.holdOf
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.take
import me.raddatz.veyq.core.moderation.hold.HoldEntity
import me.raddatz.veyq.core.moderation.hold.Holds
import me.raddatz.veyq.core.moderation.report.ModerationReportEntity
import me.raddatz.veyq.core.moderation.report.ReportTestKit.asMember
import me.raddatz.veyq.core.moderation.report.ReportTestKit.body
import me.raddatz.veyq.core.moderation.report.ReportTestKit.item
import me.raddatz.veyq.core.moderation.report.ReportTestKit.openCase
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * A child-sexual-abuse case's hold follows § 184b(5) StGB (ADR 0093 §§ 4, 13; uc.moderation.report-to-an-authority):
 * keeping the content is exempt only while it serves exclusively the report to an authority, so the hold ends at the
 * recorded handover and at the latest 90 days after the report — a measure or a statement never moves it further, a
 * logged extension may, and an authority order's end (the floor) always wins. Each rule has its control: the same step on
 * a case of another category moves the hold to six months, and a handover there ends nothing (`AuthorityOrdersSliceTest`).
 * The clock is the purge's argument and the stored times, never a sleep.
 */
@QuarkusTest
class ChildAbuseHoldSliceTest {

    @Inject
    lateinit var purge: ModerationPurge

    private data class Reported(val userId: String, val scopeId: String, val folder: String, val caseId: String, val reportedAt: Instant)

    private fun reported(category: String): Reported {
        val m = seedMemberScope(ScopeEntity.GROUP)
        val folder = seedNode(m.scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        asMember(m.userId, m.scopeId, body(listOf(item("NODE", folder)), category = category)).then().statusCode(201)
        val caseId = openCase("NODE", folder)!!.id
        val at = QuarkusTransaction.requiringNew().call { ModerationReportEntity.findAll().list().maxOf { it.receivedAt } }
        return Reported(m.userId, m.scopeId, folder, caseId, at)
    }

    private fun events(caseId: String, event: String): Long =
        QuarkusTransaction.requiringNew().call { ModerationEventEntity.count("caseId = ?1 and event = ?2", caseId, event) }

    @Test
    fun aMeasureNeverMovesTheHoldPastNinetyDaysAndTheHoldEndsThere() {
        val mod = CaseTestKit.moderator()
        val r = reported("CHILD_SEXUAL_ABUSE")
        val bound = r.reportedAt.plus(Holds.CHILD_SEXUAL_ABUSE)
        assertEquals(bound, holdOf(r.caseId)!!.ceilingUntil)
        mod.take(r.caseId, "CONTENT", targetId = r.folder, ground = "CHILD_SEXUAL_ABUSE").then().statusCode(201)
            .body("state", equalTo("DECIDED")).body("hold.trigger", equalTo("CASE_OPEN"))
        assertEquals(bound, holdOf(r.caseId)!!.expiresAt, "a measure moved a child-sexual-abuse hold past 90 days")
        // Before the bound the hold stands; after it the cleanup ends it, and the decided case closes.
        purge.sweep(bound.minus(1, ChronoUnit.HOURS))
        assertNotNull(holdOf(r.caseId))
        purge.sweep(bound.plus(1, ChronoUnit.MINUTES))
        assertNull(holdOf(r.caseId), "the hold outlived 90 days after the report")
        assertEquals(1L, events(r.caseId, "HOLD_ENDED"))
        assertEquals("CLOSED", CaseTestKit.caseRow(r.caseId).state)

        // Control: the same step on a case of another category moves its hold to six months after the statement.
        val other = reported("ILLEGAL_CONTENT")
        mod.take(other.caseId, "CONTENT", targetId = other.folder).then().statusCode(201).body("hold.trigger", equalTo("MEASURE"))
        assertTrue(holdOf(other.caseId)!!.expiresAt.isAfter(Instant.now().plus(Holds.SIX_MONTHS).minus(1, ChronoUnit.HOURS)))
        assertNull(holdOf(other.caseId)!!.ceilingUntil)
    }

    @Test
    fun anAuthoritysDeferralOfTheStatementDoesNotMoveItEither() {
        val mod = CaseTestKit.moderator()
        val r = reported("CHILD_SEXUAL_ABUSE")
        mod.take(r.caseId, "CONTENT", targetId = r.folder, ground = "CHILD_SEXUAL_ABUSE", deferUntil = Instant.now().plus(40, ChronoUnit.DAYS).toString())
            .then().statusCode(201)
        assertEquals(r.reportedAt.plus(Holds.CHILD_SEXUAL_ABUSE), holdOf(r.caseId)!!.expiresAt)
    }

    @Test
    fun theHandoverEndsTheHoldAndTheExportSaysNothingIsHeldThen() {
        val mod = CaseTestKit.moderator()
        val r = reported("CHILD_SEXUAL_ABUSE")
        // Within the window the export works and says what is held.
        mod.export(r.caseId).then().statusCode(200).body("held", equalTo(true)).body("category", equalTo("CHILD_SEXUAL_ABUSE"))
        mod.handover(r.caseId, "REPORTED").then().statusCode(201)
        assertNull(holdOf(r.caseId), "the handover left the content held")
        assertEquals(1L, events(r.caseId, "HOLD_ENDED"))
        assertEquals(1L, events(r.caseId, "HANDED_OVER"))
        // Once it ended the export claims nothing held: no uploader, no address, `held` false; the case's own fields stay.
        mod.export(r.caseId).then().statusCode(200).body("held", equalTo(false))
            .body("uploaders.size()", equalTo(0)).body("guestWrites.size()", equalTo(0)).body("reportedAt.size()", equalTo(1))
        // Undecided, the case stays in the queue: a moderator still owes the decision.
        mod.case(r.caseId).then().body("state", equalTo("OPEN"))

        // Decided first, the handover ends the hold and closes the case; information given counts as a handover too.
        val decided = reported("CHILD_SEXUAL_ABUSE")
        mod.take(decided.caseId, "CONTENT", targetId = decided.folder, ground = "CHILD_SEXUAL_ABUSE").then().statusCode(201)
        mod.handover(decided.caseId, "INFORMATION_GIVEN").then().statusCode(201)
        assertNull(holdOf(decided.caseId))
        assertEquals("CLOSED", CaseTestKit.caseRow(decided.caseId).state)
    }

    @Test
    fun anAuthorityOrdersFloorOutlivesTheHandover() {
        val mod = CaseTestKit.moderator()
        val r = reported("CHILD_SEXUAL_ABUSE")
        val floor = Instant.now().plus(200, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS)
        // An authority's order set this hold's floor (as a preservation order's end does): it outlives every other end.
        QuarkusTransaction.requiringNew().run { HoldEntity.find("caseId", r.caseId).firstResult()!!.floorUntil = floor }
        mod.take(r.caseId, "CONTENT", targetId = r.folder, ground = "CHILD_SEXUAL_ABUSE").then().statusCode(201)
        mod.handover(r.caseId, "REPORTED").then().statusCode(201)
        val held = holdOf(r.caseId)
        assertNotNull(held, "the handover ended a hold an authority's order keeps")
        assertEquals(floor, held!!.expiresAt)
        assertEquals("DECIDED", CaseTestKit.caseRow(r.caseId).state)
    }

    @Test
    fun aLoggedExtensionMayPassTheBoundAndTheHandoverStillEndsIt() {
        val mod = CaseTestKit.moderator()
        val r = reported("CHILD_SEXUAL_ABUSE")
        val until = Instant.now().plus(150, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS)
        mod.extend(r.caseId, until.toString()).then().statusCode(200).body("hold.trigger", equalTo("EXTENDED"))
        // A measure after it neither lengthens nor shortens the extension.
        mod.take(r.caseId, "CONTENT", targetId = r.folder, ground = "CHILD_SEXUAL_ABUSE").then().statusCode(201)
        assertEquals(until, holdOf(r.caseId)!!.expiresAt)
        mod.handover(r.caseId, "REPORTED").then().statusCode(201)
        assertNull(holdOf(r.caseId))
    }

    @Test
    fun aReportThatMakesADecidedCaseOneOfChildSexualAbuseBringsItsHoldBackToNinetyDays() {
        val mod = CaseTestKit.moderator()
        val r = reported("TERMS_BREACH")
        mod.take(r.caseId, "CONTENT", targetId = r.folder).then().statusCode(201)
        assertTrue(holdOf(r.caseId)!!.expiresAt.isAfter(Instant.now().plus(100, ChronoUnit.DAYS)))
        asMember(r.userId, r.scopeId, body(listOf(item("NODE", r.folder)), category = "CHILD_SEXUAL_ABUSE")).then().statusCode(201)
        val second = QuarkusTransaction.requiringNew().call { ModerationReportEntity.findAll().list().maxOf { it.receivedAt } }
        val hold = holdOf(r.caseId)!!
        assertEquals(second.plus(Holds.CHILD_SEXUAL_ABUSE), hold.ceilingUntil)
        assertEquals(second.plus(Holds.CHILD_SEXUAL_ABUSE), hold.expiresAt, "a measure's six months outlived the category")
        assertEquals("CHILD_SEXUAL_ABUSE", CaseTestKit.caseRow(r.caseId).category)
        assertFalse(hold.expiresAt.isAfter(Instant.now().plus(Holds.CHILD_SEXUAL_ABUSE)))
    }
}
