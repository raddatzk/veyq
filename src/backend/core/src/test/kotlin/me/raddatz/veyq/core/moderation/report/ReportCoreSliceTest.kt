package me.raddatz.veyq.core.moderation.report

import me.raddatz.veyq.core.moderation.ModerationEventEntity
import me.raddatz.veyq.core.moderation.report.ReportTestKit.SEALED_CONTACT
import me.raddatz.veyq.core.moderation.report.ReportTestKit.SEALED_REPORT
import me.raddatz.veyq.core.moderation.report.ReportTestKit.asGuest
import me.raddatz.veyq.core.moderation.report.ReportTestKit.asMember
import me.raddatz.veyq.core.moderation.report.ReportTestKit.body
import me.raddatz.veyq.core.moderation.report.ReportTestKit.epochOne
import me.raddatz.veyq.core.moderation.report.ReportTestKit.item
import me.raddatz.veyq.core.moderation.report.ReportTestKit.openCase
import me.raddatz.veyq.core.moderation.report.ReportTestKit.report
import me.raddatz.veyq.core.moderation.report.ReportTestKit.reportsOf
import me.raddatz.veyq.core.moderation.report.ReportTestKit.share
import me.raddatz.veyq.core.moderation.report.ReportTestKit.sharesOf
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Receiving a report with core alone (uc.moderation.report; `FOLDER` nodes and a `FOLDER` share — core shares a folder,
 * `ShareRoots`): who may report what, one case per item, and what the server keeps in the clear. A medium (an
 * application's item) and an upload link are Memories' (`ReportMemoriesSliceTest`).
 */
@QuarkusTest
class ReportCoreSliceTest {

    @BeforeEach
    fun epoch() {
        epochOne()
    }

    @Test
    fun aMemberReportsANodeOfTheirSpaceAndTheServerKeepsOnlyTheClearFields() {
        val m = seedMemberScope(ScopeEntity.GROUP)
        val folder = seedNode(m.scopeId, "FOLDER", wrappedContentKey = CONTENT_KEY)

        val reportId = asMember(m.userId, m.scopeId, body(listOf(item("NODE", folder)), category = "TERMS_BREACH"))
            .then().statusCode(201).body("reportId", notNullValue()).body("receivedAt", notNullValue())
            .extract().path<String>("reportId")

        val stored = report(reportId)!!
        assertEquals(1, stored.epoch)
        assertEquals("TERMS_BREACH", stored.category)
        assertEquals(SEALED_REPORT, stored.sealedReport, "the sealed part is stored as it was sent")
        assertEquals(m.userId, stored.reporterAccount)
        assertNull(stored.reporterShare)
        val case = openCase("NODE", folder)!!
        assertEquals(m.scopeId, case.scopeId)
        assertEquals("OPEN", case.state)
        assertEquals(listOf(reportId), sharesOf(case.id).map { it.reportId })
        assertEquals(listOf("RECEIVED"), events(case.id))
    }

    /** ADR 0093 § 11: the address is sealed apart from the report, so it can go alone; none left, none stored. */
    @Test
    fun theAddressIsStoredSealedApartOnlyWhenOneWasLeft() {
        val m = seedMemberScope(ScopeEntity.GROUP)
        val left = seedNode(m.scopeId, "FOLDER", wrappedContentKey = CONTENT_KEY)
        val none = seedNode(m.scopeId, "FOLDER", wrappedContentKey = CONTENT_KEY)
        val withAddress = asMember(m.userId, m.scopeId, body(listOf(item("NODE", left)), sealedContact = SEALED_CONTACT))
            .then().statusCode(201).extract().path<String>("reportId")
        val without = asMember(m.userId, m.scopeId, body(listOf(item("NODE", none))))
            .then().statusCode(201).extract().path<String>("reportId")
        assertEquals(SEALED_CONTACT, report(withAddress)!!.sealedContact)
        assertEquals(SEALED_REPORT, report(withAddress)!!.sealedReport)
        assertNull(report(without)!!.sealedContact)
        // A sealed contact that is not base64 is refused like any other malformed field.
        asMember(m.userId, m.scopeId, body(listOf(item("NODE", none)), sealedContact = "not base64!"))
            .then().statusCode(400)
    }

    @Test
    fun aNodeOfAnotherSpaceRefusesTheWholeReportAndAStrangersSpaceIsTheUnknownScope() {
        val m = seedMemberScope(ScopeEntity.GROUP)
        val mine = seedNode(m.scopeId, "FOLDER", wrappedContentKey = CONTENT_KEY)
        val other = seedMemberScope(ScopeEntity.GROUP)
        val theirs = seedNode(other.scopeId, "FOLDER", wrappedContentKey = CONTENT_KEY)

        asMember(m.userId, m.scopeId, body(listOf(item("NODE", mine), item("NODE", theirs))))
            .then().statusCode(404).body("code", equalTo("report_item_not_found"))
        asMember(m.userId, other.scopeId, body(listOf(item("NODE", theirs))))
            .then().statusCode(404).body("code", equalTo("scope_not_found"))
        assertEquals(0L, reportsOf(m.userId), "a refused report stores nothing")
        assertNull(openCase("NODE", mine), "not even for the item that was in reach")
        // The positive control: the same member's node alone is received.
        asMember(m.userId, m.scopeId, body(listOf(item("NODE", mine)))).then().statusCode(201)
    }

    @Test
    fun theSameItemTwiceIsOneCaseWithTwoReportsAndTheMoreUrgentCategory() {
        val m = seedMemberScope(ScopeEntity.GROUP)
        val folder = seedNode(m.scopeId, "FOLDER", wrappedContentKey = CONTENT_KEY)

        val first = asMember(m.userId, m.scopeId, body(listOf(item("NODE", folder)), category = "TERMS_BREACH"))
            .then().statusCode(201).extract().path<String>("reportId")
        val second = asMember(m.userId, m.scopeId, body(listOf(item("NODE", folder)), category = "THREAT_TO_LIFE"))
            .then().statusCode(201).extract().path<String>("reportId")
        asMember(m.userId, m.scopeId, body(listOf(item("NODE", folder)), category = "ILLEGAL_CONTENT")).then().statusCode(201)

        val case = openCase("NODE", folder)!!
        assertEquals(1L, QuarkusTransaction.requiringNew().call { ModerationCaseEntity.count("itemId", folder) }, "one case")
        assertEquals(3, sharesOf(case.id).size, "every report attached")
        assertEquals(setOf(first, second), sharesOf(case.id).map { it.reportId }.toSet() intersect setOf(first, second))
        assertEquals("THREAT_TO_LIFE", case.category, "the most urgent of its reports, never lowered again")
        assertEquals(listOf("RECEIVED", "REPORT_ATTACHED", "REPORT_ATTACHED"), events(case.id))
    }

    @Test
    fun aGuestReportsInsideTheLinkAndIsRefusedOutsideIt() {
        val m = seedMemberScope(ScopeEntity.GROUP)
        val shared = seedNode(m.scopeId, "FOLDER", wrappedContentKey = CONTENT_KEY)
        val inside = seedNode(m.scopeId, "FOLDER", parentId = shared, wrappedContentKey = CONTENT_KEY)
        val outside = seedNode(m.scopeId, "FOLDER", wrappedContentKey = CONTENT_KEY)
        val shareId = share(m.userId, shared)

        asGuest(shareId, body(listOf(item("NODE", outside)))).then().statusCode(404).body("code", equalTo("report_item_not_found"))
        assertNull(openCase("NODE", outside))

        val reportId = asGuest(shareId, body(listOf(item("NODE", inside), item("NODE", shared))))
            .then().statusCode(201).extract().path<String>("reportId")
        assertEquals(shareId, report(reportId)!!.reporterShare)
        assertNull(report(reportId)!!.reporterAccount)
        assertNotNull(openCase("NODE", inside))
        assertNotNull(openCase("NODE", shared))
    }

    @Test
    fun aGuestWithoutTheWholeLinkIsTheUnknownLinkAndAViewingGuestReportsNoLink() {
        val m = seedMemberScope(ScopeEntity.GROUP)
        val shared = seedNode(m.scopeId, "FOLDER", wrappedContentKey = CONTENT_KEY)
        val shareId = share(m.userId, shared)

        asGuest(shareId, body(listOf(item("NODE", shared))), writeProof = false)
            .then().statusCode(404).body("code", equalTo("share_not_found"))
        asGuest("A".repeat(43), body(listOf(item("NODE", shared)))).then().statusCode(404).body("code", equalTo("share_not_found"))
        // A READ guest reports what the link shows; the link itself is an upload link's guest's item (ADR 0093 § 1) — a
        // well-formed item outside this guest's reach, so the reach's 404, never the shape's 400 (inbox #507).
        asGuest(shareId, body(listOf(item("LINK", null)))).then().statusCode(404).body("code", equalTo("report_item_not_found"))
        assertNull(openCase("NODE", shared))
    }

    @Test
    fun aReportSealedToAnotherEpochIsRefused() {
        val m = seedMemberScope(ScopeEntity.GROUP)
        val folder = seedNode(m.scopeId, "FOLDER", wrappedContentKey = CONTENT_KEY)
        asMember(m.userId, m.scopeId, body(listOf(item("NODE", folder)), epoch = 2))
            .then().statusCode(409).body("code", equalTo("moderation_epoch_changed"))
        assertNull(openCase("NODE", folder))
    }

    @Test
    fun aMalformedReportIsRefusedBeforeAnythingIsStored() {
        val m = seedMemberScope(ScopeEntity.GROUP)
        val folder = seedNode(m.scopeId, "FOLDER", wrappedContentKey = CONTENT_KEY)
        // The shape the contract states (inbox #507): a node without its id; a LINK with one; no item; 101 items.
        for (items in listOf(
            listOf(item("NODE", null)),
            listOf(item("LINK", folder)),
            emptyList(),
            List(101) { item("NODE", UUID.randomUUID().toString()) },
        )) {
            asMember(m.userId, m.scopeId, body(items)).then().statusCode(400).body("code", equalTo("invalid_request"))
        }
        // What no schema states: the same item twice — the same kind and id, whatever its sealed share says.
        asMember(m.userId, m.scopeId, body(listOf(item("NODE", folder), item("NODE", folder))))
            .then().statusCode(422).body("code", equalTo("report_item_repeated"))
        // A member's LINK is well-formed and names nothing in reach: a member's request comes through no link.
        asMember(m.userId, m.scopeId, body(listOf(item("LINK", null))))
            .then().statusCode(404).body("code", equalTo("report_item_not_found"))
        assertEquals(0L, reportsOf(m.userId))
    }

    @Test
    fun aMemberWithTooManyOpenReportsIsRefusedUntilCasesClose() {
        val m = seedMemberScope(ScopeEntity.GROUP)
        val folder = seedNode(m.scopeId, "FOLDER", wrappedContentKey = CONTENT_KEY)
        // 100 open reports of this member, each on a case of its own (the cap's default, owner decision Q8).
        val caseIds = QuarkusTransaction.requiringNew().call {
            List(100) {
                val case = ModerationCaseEntity().apply {
                    id = UUID.randomUUID().toString(); itemKind = "NODE"; itemId = UUID.randomUUID().toString()
                    scopeId = m.scopeId; category = "TERMS_BREACH"; state = "OPEN"; openKey = "NODE:$itemId"
                }.also { it.persist() }
                val r = ModerationReportEntity().apply {
                    id = UUID.randomUUID().toString(); epoch = 1; receivedAt = Instant.now(); category = "TERMS_BREACH"
                    sealedReport = SEALED_REPORT; reporterAccount = m.userId
                }.also { it.persist() }
                ReportShareEntity().apply {
                    id = UUID.randomUUID().toString(); reportId = r.id; caseId = case.id; sealedKeys = "a2V5cw=="
                }.persist()
                case.id
            }
        }
        asMember(m.userId, m.scopeId, body(listOf(item("NODE", folder))))
            .then().statusCode(429).body("code", equalTo("too_many_open_reports"))

        // One case closes: the next report is received.
        QuarkusTransaction.requiringNew().run {
            ModerationCaseEntity.update("state = 'CLOSED', openKey = null where id = ?1", caseIds.first())
        }
        asMember(m.userId, m.scopeId, body(listOf(item("NODE", folder)))).then().statusCode(201)
    }

    @Test
    fun aTrashedNodeIsNotWhatTheReporterSees() {
        val m = seedMemberScope(ScopeEntity.GROUP)
        val folder = seedNode(m.scopeId, "FOLDER", wrappedContentKey = CONTENT_KEY)
        QuarkusTransaction.requiringNew().run { NodeEntity.update("deletedAt = ?1 where id = ?2", Instant.now(), folder) }
        asMember(m.userId, m.scopeId, body(listOf(item("NODE", folder)))).then().statusCode(404).body("code", equalTo("report_item_not_found"))
    }

    private fun events(caseId: String): List<String> = QuarkusTransaction.requiringNew().call {
        ModerationEventEntity.list("caseId = ?1 order by occurredAt, id", caseId).map { it.event }
    }
}
