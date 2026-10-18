package me.raddatz.veyq.core.moderation

import me.raddatz.veyq.core.moderation.cases.CaseDecisions
import me.raddatz.veyq.core.moderation.cases.CaseTestKit
import me.raddatz.veyq.core.moderation.cases.DecisionOutcome
import me.raddatz.veyq.core.moderation.cases.HandoverKind
import me.raddatz.veyq.core.moderation.cases.SealedNote
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.dismiss
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.handover
import me.raddatz.veyq.core.moderation.report.ModerationCaseEntity
import me.raddatz.veyq.core.moderation.report.ReportTestKit.asMember
import me.raddatz.veyq.core.moderation.report.ReportTestKit.body
import me.raddatz.veyq.core.moderation.report.ReportTestKit.item
import me.raddatz.veyq.core.moderation.report.ReportTestKit.openCase
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.RACE_ROUNDS
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.whileUncommitted
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.temporal.ChronoUnit

/**
 * The records purge against a decision on the same case at once (ADR 0093 § 11; `ModerationRecordsPurge`), run on MariaDB
 * by `scripts/run_backend_races_on_mariadb.py`. The purge takes the case row first, as every decision does, so a handover
 * recorded inside its window waits, then finds the case gone — **`404 case_not_found`, and no event left behind with no
 * case** — never an event written onto a case the purge has already emptied; and a purge inside a handover's window
 * waits for it, reads its fresh event and keeps the case. Forced with [whileUncommitted]: one side's writes held
 * uncommitted, the other sent inside the window.
 */
@QuarkusTest
class ModerationRecordsRaceSliceTest {

    @Inject
    lateinit var records: ModerationRecordsPurge

    @Inject
    lateinit var decisions: CaseDecisions

    @Test
    fun aHandoverInsideThePurgesWindowFindsTheCaseGoneAndLeavesNothing() {
        val mod = CaseTestKit.moderator()
        repeat(RACE_ROUNDS) { round ->
            val m = seedMemberScope(ScopeEntity.GROUP)
            val folder = seedNode(m.scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
            asMember(m.userId, m.scopeId, body(listOf(item("NODE", folder)))).then().statusCode(201)
            val caseId = openCase("NODE", folder)!!.id
            mod.dismiss(caseId).then().statusCode(200)
            val due = ModerationLog.retentionEnd(CaseTestKit.caseRow(caseId).closedAt!!).plus(1, ChronoUnit.MINUTES)
            val answered = whileUncommitted(
                change = { assertTrue(records.purgeCaseIn(caseId, due), "round $round: the purge kept a due case") },
                overlapping = { mod.handover(caseId).then().extract() },
            )
            assertEquals(404, answered.statusCode(), "round $round: a handover onto a purged case — ${answered.body().asString()}")
            QuarkusTransaction.requiringNew().run {
                assertEquals(0L, ModerationCaseEntity.count("id", caseId), "round $round")
                assertEquals(0L, ModerationEventEntity.count("caseId", caseId), "round $round: an event outlived its case")
            }
        }
    }

    /**
     * The other order: a handover on the due case held uncommitted, the purge sent inside its window. The purge waits for
     * the case row, then reads the handover's event — dated from the handover, so years ahead — and keeps the case. Bite:
     * read the case without its lock in `purgeCaseIn` and the purge, reading the events before the handover commits,
     * deletes the case and the handover's event with it.
     */
    @Test
    fun aPurgeInsideAHandoversWindowSeesItsEventAndKeepsTheCase() {
        val mod = CaseTestKit.moderator()
        repeat(RACE_ROUNDS) { round ->
            val m = seedMemberScope(ScopeEntity.GROUP)
            val folder = seedNode(m.scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
            asMember(m.userId, m.scopeId, body(listOf(item("NODE", folder)))).then().statusCode(201)
            val caseId = openCase("NODE", folder)!!.id
            mod.dismiss(caseId).then().statusCode(200)
            val due = ModerationLog.retentionEnd(CaseTestKit.caseRow(caseId).closedAt!!).plus(1, ChronoUnit.MINUTES)
            val purged = whileUncommitted(
                change = {
                    val done = decisions.handoverIn(mod.id, caseId, HandoverKind.REPORTED, SealedNote(1, CaseTestKit.SEALED_NOTE), due)
                    assertEquals(DecisionOutcome.Done, done, "round $round")
                },
                overlapping = { QuarkusTransaction.requiringNew().call { records.purgeCaseIn(caseId, due) } },
            )
            assertFalse(purged, "round $round: the purge deleted a case a handover had just dated again")
            QuarkusTransaction.requiringNew().run {
                assertEquals(1L, ModerationCaseEntity.count("id", caseId), "round $round")
                assertEquals(1L, ModerationEventEntity.count("caseId = ?1 and event = ?2", caseId, "HANDED_OVER"), "round $round")
            }
        }
    }
}
