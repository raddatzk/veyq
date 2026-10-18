package me.raddatz.veyq.core.moderation.cases

import me.raddatz.veyq.core.moderation.ModerationEventEntity
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.bar
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.case
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.dismiss
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.extend
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.lift
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.queue
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.take
import me.raddatz.veyq.core.moderation.report.ReportTestKit.asGuest
import me.raddatz.veyq.core.moderation.report.ReportTestKit.asMember
import me.raddatz.veyq.core.moderation.report.ReportTestKit.body
import me.raddatz.veyq.core.moderation.report.ReportTestKit.item
import me.raddatz.veyq.core.moderation.report.ReportTestKit.openCase
import me.raddatz.veyq.core.moderation.report.ReportTestKit.share
import me.raddatz.veyq.core.moderation.hold.Holds
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.hamcrest.Matchers.contains
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * A moderator's queue and decisions (uc.moderation.review-a-case, uc.moderation.take-a-measure; ADR 0093 §§ 4-6) with
 * core alone: a member reports a folder, the Server Owner — the first moderator — decides. Each refusal has its control on
 * the same predicate; the content block's byte paths are the applications' tests' (`ContentBlockMemoriesSliceTest`).
 */
@QuarkusTest
class ModerationCasesSliceTest {

    private data class Reported(val userId: String, val scopeId: String, val folder: String, val caseId: String)

    private fun reported(category: String = "ILLEGAL_CONTENT", scopeKind: String = ScopeEntity.GROUP): Reported {
        val m = seedMemberScope(scopeKind)
        val folder = seedNode(m.scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        asMember(m.userId, m.scopeId, body(listOf(item("NODE", folder)), category = category)).then().statusCode(201)
        return Reported(m.userId, m.scopeId, folder, openCase("NODE", folder)!!.id)
    }

    @Test
    fun theQueueListsTheInstancesCasesInClearFieldsUrgentFirst() {
        val mod = CaseTestKit.moderator()
        val illegal = reported("ILLEGAL_CONTENT")
        val urgent = reported("THREAT_TO_LIFE")
        mod.queue().then().statusCode(200)
            .body("cases.size()", equalTo(2))
            .body("cases[0].id", equalTo(urgent.caseId))
            .body("cases[0].category", equalTo("THREAT_TO_LIFE"))
            .body("cases[1].id", equalTo(illegal.caseId))
            .body("cases[1].itemKind", equalTo("NODE"))
            .body("cases[1].state", equalTo("OPEN"))
            .body("cases[1].reportCount", equalTo(1))
            .body("cases[1].tenantDomain", notNullValue())
            .body("cases[1].holdEndsAt", notNullValue())
        // A dismissed case leaves the default queue and is listed as closed.
        mod.dismiss(illegal.caseId).then().statusCode(200)
        mod.queue().then().statusCode(200).body("cases.size()", equalTo(1))
        mod.queue("CLOSED").then().statusCode(200).body("cases.find { it.id == '${illegal.caseId}' }.state", equalTo("CLOSED"))
    }

    @Test
    fun aCaseCarriesItsSealedReportsHoldTargetsAndLog() {
        val mod = CaseTestKit.moderator()
        val r = reported()
        mod.case(r.caseId).then().statusCode(200)
            .body("itemId", equalTo(r.folder))
            .body("reports.size()", equalTo(1))
            .body("reports[0].sealedReport", equalTo("AXNlYWxlZC1yZXBvcnQ="))
            .body("reports[0].reporter", equalTo("ACCOUNT"))
            .body("reports[0].barred", equalTo(false))
            .body("hold.trigger", equalTo("CASE_OPEN"))
            .body("events[0].event", equalTo("RECEIVED"))
            .body("measures.size()", equalTo(0))
        // An unknown case is the case's own 404, after the proof (a proof over another case answers nothing).
        mod.case("00000000-0000-4000-8000-000000000000").then().statusCode(404).body("code", equalTo("case_not_found"))
    }

    @Test
    fun aDismissalClosesTheCaseEndsItsHoldAtOnceAndDatesItsEvents() {
        val mod = CaseTestKit.moderator()
        val r = reported()
        val hold = CaseTestKit.holdOf(r.caseId)!!
        assertTrue(CaseTestKit.pinsOf(hold.id) >= 0)
        mod.dismiss(r.caseId, note = true).then().statusCode(200)
            .body("state", equalTo("CLOSED")).body("closedAt", notNullValue()).body("hold", nullValue())
        assertNull(CaseTestKit.holdOf(r.caseId), "the hold ends at once")
        val events = QuarkusTransaction.requiringNew().call { ModerationEventEntity.list("caseId", r.caseId) }
        assertTrue(events.any { it.event == "DISMISSED" && it.sealedText == CaseTestKit.SEALED_NOTE })
        assertTrue(events.all { it.expiresAt != null }, "a closed case's events are dated")
        // A dismissed case is not dismissed again; a new report of the item opens a new case (one open case per item).
        mod.dismiss(r.caseId).then().statusCode(409).body("code", equalTo("case_not_open"))
        asMember(r.userId, r.scopeId, body(listOf(item("NODE", r.folder)))).then().statusCode(201)
        val again = openCase("NODE", r.folder)!!
        assertTrue(again.id != r.caseId)
        assertNotNull(CaseTestKit.holdOf(again.id), "the new case takes its own hold")
    }

    @Test
    fun aMeasureMovesTheHoldToSixMonthsAndLiftingTheLastOneEndsItAndClosesTheCase() {
        val mod = CaseTestKit.moderator()
        val r = reported()
        val before = CaseTestKit.holdOf(r.caseId)!!.expiresAt
        val measure = mod.take(r.caseId, "CONTENT", targetId = r.folder).then().statusCode(201)
            .body("state", equalTo("DECIDED")).body("decidedAt", notNullValue()).body("hold.trigger", equalTo("MEASURE"))
            .extract().path<String>("measures[0].id")
        val after = CaseTestKit.holdOf(r.caseId)!!.expiresAt
        assertTrue(after.isAfter(before) && after.isAfter(Instant.now().plus(Holds.SIX_MONTHS).minus(1, ChronoUnit.HOURS)))
        // A decided case is not dismissed; the same step twice is refused.
        mod.dismiss(r.caseId).then().statusCode(409).body("code", equalTo("case_not_open"))
        mod.take(r.caseId, "CONTENT", targetId = r.folder).then().statusCode(409).body("code", equalTo("moderation_measure_exists"))
        mod.lift(r.caseId, measure).then().statusCode(200)
            .body("state", equalTo("CLOSED")).body("hold", nullValue()).body("measures[0].liftedAt", notNullValue())
        assertNull(CaseTestKit.holdOf(r.caseId), "the lift of the last measure ends the hold")
        // Lifting twice changes nothing.
        mod.lift(r.caseId, measure).then().statusCode(200)
    }

    @Test
    fun aStepOnATargetTheCaseDoesNotKnowIsRefusedAndTheKnownOneIsTaken() {
        val mod = CaseTestKit.moderator()
        val r = reported()
        val other = reported()
        // Another case's node, and a link the case does not know.
        mod.take(r.caseId, "CONTENT", targetId = other.folder).then().statusCode(404).body("code", equalTo("moderation_target_unknown"))
        mod.take(r.caseId, "SHARE", shareId = "A".repeat(43)).then().statusCode(404).body("code", equalTo("moderation_target_unknown"))
        mod.take(r.caseId, "GUEST_IDENTITY", targetId = other.folder).then().statusCode(404).body("code", equalTo("moderation_target_unknown"))
        // A kind and a target that disagree.
        mod.take(r.caseId, "SHARE", targetId = r.folder).then().statusCode(400).body("code", equalTo("invalid_request"))
        // Control: the reported node itself, and a link rooted at it.
        val link = share(r.userId, r.folder)
        mod.take(r.caseId, "SHARE", shareId = link).then().statusCode(201).body("targets.links.find { it.shareId == '$link' }.blocked", equalTo(true))
        mod.take(r.caseId, "CONTENT", targetId = r.folder).then().statusCode(201)
    }

    @Test
    fun anExtensionNeedsAnAliveHoldAReasonAndABound() {
        val mod = CaseTestKit.moderator()
        val r = reported()
        val end = CaseTestKit.holdOf(r.caseId)!!.expiresAt
        // Not after the current end; more than six months from now; a reason sealed to another epoch.
        mod.extend(r.caseId, end.minus(1, ChronoUnit.DAYS).toString()).then().statusCode(400).body("code", equalTo("extension_out_of_bounds"))
        mod.extend(r.caseId, Instant.now().plus(200, ChronoUnit.DAYS).toString()).then().statusCode(400).body("code", equalTo("extension_out_of_bounds"))
        val until = end.plus(30, ChronoUnit.DAYS)
        mod.extend(r.caseId, until.toString(), epoch = 2).then().statusCode(409).body("code", equalTo("moderation_epoch_changed"))
        // Control: within the bound.
        mod.extend(r.caseId, until.toString()).then().statusCode(200).body("hold.trigger", equalTo("EXTENDED"))
        assertEquals(until.truncatedTo(ChronoUnit.MILLIS), CaseTestKit.holdOf(r.caseId)!!.expiresAt.truncatedTo(ChronoUnit.MILLIS))
        mod.case(r.caseId).then().body("events.find { it.event == 'HOLD_EXTENDED' }.sealedText", equalTo(CaseTestKit.SEALED_NOTE))
            .body("events.find { it.event == 'HOLD_EXTENDED' }.until", notNullValue())
        // Nothing held: a dismissed case is extended no more.
        mod.dismiss(r.caseId).then().statusCode(200)
        mod.extend(r.caseId, until.toString()).then().statusCode(409).body("code", equalTo("nothing_held"))
    }

    @Test
    fun aBarredMemberReportsNothingUntilTheBarIsLifted() {
        val mod = CaseTestKit.moderator()
        val r = reported()
        val reportId = mod.case(r.caseId).then().extract().path<String>("reports[0].id")
        val another = seedNode(r.scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val bar = mod.bar(r.caseId, reportId).then().statusCode(201).body("reports[0].barred", equalTo(true))
            .body("state", equalTo("OPEN")) // a bar is no decision on the content
            .body("events.findAll { it.event == 'REPORTER_BARRED' }.detail", contains("REPORTER ${r.userId}")) // logged, naming the account
            .extract().path<String>("measures[0].id")
        mod.bar(r.caseId, reportId).then().statusCode(409).body("code", equalTo("moderation_measure_exists"))
        asMember(r.userId, r.scopeId, body(listOf(item("NODE", another)))).then().statusCode(403).body("code", equalTo("reporter_barred"))
        mod.lift(r.caseId, bar).then().statusCode(200).body("state", equalTo("OPEN"))
        asMember(r.userId, r.scopeId, body(listOf(item("NODE", another)))).then().statusCode(201)
    }

    @Test
    fun aBarredLinkTakesNoGuestReportsUntilLifted() {
        val mod = CaseTestKit.moderator()
        val m = seedMemberScope(ScopeEntity.GROUP)
        val folder = seedNode(m.scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val sub = seedNode(m.scopeId, NodeEntity.FOLDER, parentId = folder, wrappedContentKey = CONTENT_KEY)
        val link = share(m.userId, folder)
        asGuest(link, body(listOf(item("NODE", folder)))).then().statusCode(201)
        val caseId = openCase("NODE", folder)!!.id
        val reportId = mod.case(caseId).then().body("reports[0].reporter", equalTo("LINK")).extract().path<String>("reports[0].id")
        val bar = mod.bar(caseId, reportId).then().statusCode(201)
            .body("events.findAll { it.event == 'REPORTER_BARRED' }.detail", contains("REPORTER $link")) // logged, naming the link
            .extract().path<String>("measures[0].id")
        asGuest(link, body(listOf(item("NODE", sub)))).then().statusCode(403).body("code", equalTo("reporter_barred"))
        // The bar is on reporting only: the link still serves its guests.
        io.restassured.RestAssured.given().`when`().get("/api/shares/$link").then().statusCode(200)
        mod.lift(caseId, bar).then().statusCode(200)
        asGuest(link, body(listOf(item("NODE", sub)))).then().statusCode(201)
    }
}
