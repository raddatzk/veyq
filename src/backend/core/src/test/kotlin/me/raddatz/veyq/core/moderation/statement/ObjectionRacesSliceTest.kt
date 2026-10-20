package me.raddatz.veyq.core.moderation.statement

import me.raddatz.veyq.core.moderation.ModerationEventEntity
import me.raddatz.veyq.core.moderation.ModerationGrantEntity
import me.raddatz.veyq.core.moderation.ModerationTestKit
import me.raddatz.veyq.core.moderation.ModerationTestKit.appointAndAccept
import me.raddatz.veyq.core.moderation.ModerationTestKit.asUser
import me.raddatz.veyq.core.moderation.cases.CaseDecisions
import me.raddatz.veyq.core.moderation.cases.CaseTestKit
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.case
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.decideObjection
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.take
import me.raddatz.veyq.core.moderation.cases.DecisionOutcome
import me.raddatz.veyq.core.moderation.report.ReportTestKit.asMember
import me.raddatz.veyq.core.moderation.report.ReportTestKit.body
import me.raddatz.veyq.core.moderation.report.ReportTestKit.item
import me.raddatz.veyq.core.moderation.report.ReportTestKit.openCase
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import me.raddatz.veyq.core.testing.whileUncommitted
import me.raddatz.veyq.core.testing.raceRounds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Two moderators decide one objection at once (uc.moderation.object-to-a-measure A4; ADR 0099 § 5), run on MariaDB by
 * `scripts/run_backend_races_on_mariadb.py`. A decision takes the case row first (`CaseDecisions.decideObjectionIn`), so
 * the second either waits for the first and reads the objection decided — **refused, `409 objection_decided`** — never
 * a second outcome over the first. Forced with [whileUncommitted]: the first decision's writes held uncommitted, the
 * other moderator's HTTP decision sent inside the window. Bite: read the case without its lock and the second decides too.
 */
@QuarkusTest
class ObjectionRacesSliceTest {

    @Inject
    lateinit var decisions: CaseDecisions

    @Test
    fun aSecondModeratorDecidingTheSameObjectionAtOnceIsRefused() {
        val mod = CaseTestKit.moderator()
        // Two moderators besides the one who took the measures: neither is its taker, so both may decide.
        val (first, second) = (1..2).map {
            val id = seedUser()
            appointAndAccept(mod.id, "u-$id@example.com", id)
            QuarkusTransaction.requiringNew().run {
                ModerationGrantEntity().apply {
                    this.id = UUID.randomUUID().toString(); moderatorId = id; epoch = 1; sealedGrant = ModerationTestKit.sealedGrant(); deliveredBy = mod.id
                }.persist()
            }
            CaseTestKit.Moderator(id, mod.key)
        }
        raceRounds { round ->
            val m = seedMemberScope(ScopeEntity.GROUP)
            val folder = seedNode(m.scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
            asMember(m.userId, m.scopeId, body(listOf(item("NODE", folder)))).then().statusCode(201)
            val caseId = openCase("NODE", folder)!!.id
            val statementId = mod.take(caseId, "CONTENT", targetId = folder).then().statusCode(201)
                .extract().path<String>("measures[0].statement.id")
            asUser(m.userId).contentType(ContentType.JSON).body("""{"epoch":1,"sealedObjection":"c2VhbGVkLW9iamVjdGlvbg=="}""")
                .post("/api/statements/$statementId/objection").then().statusCode(201)
            val objectionId = mod.case(caseId).then().extract().path<String>("objections[0].id")
            val answered = whileUncommitted(
                change = {
                    assertEquals(DecisionOutcome.Done, decisions.decideObjectionIn(first.id, caseId, objectionId, lift = false, note = null, now = Instant.now()))
                },
                overlapping = { second.decideObjection(caseId, objectionId, "LIFT").then().extract() },
            )
            assertEquals(409, answered.statusCode(), "round $round: a second decision over the first")
            assertEquals("objection_decided", answered.path<String>("code"), "round $round")
            QuarkusTransaction.requiringNew().run {
                val row = ModerationObjectionEntity.findById(objectionId)!!
                assertEquals("UPHELD", row.outcome, "round $round")
                assertEquals(first.id, row.decidedBy, "round $round")
                assertEquals(1L, ModerationEventEntity.count("caseId = ?1 and event = ?2", caseId, "OBJECTION_DECIDED"), "round $round")
            }
        }
    }
}
