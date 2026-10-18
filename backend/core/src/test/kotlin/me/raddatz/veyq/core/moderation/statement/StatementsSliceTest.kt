package me.raddatz.veyq.core.moderation.statement

import me.raddatz.veyq.core.admin.operator.OperatorDetailsEntity
import me.raddatz.veyq.core.admin.operator.OperatorDetailsStore
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.moderation.ModerationEventEntity
import me.raddatz.veyq.core.moderation.ModerationGrantEntity
import me.raddatz.veyq.core.moderation.ModerationPurge
import me.raddatz.veyq.core.moderation.ModerationTestKit
import me.raddatz.veyq.core.moderation.ModerationTestKit.appointAndAccept
import me.raddatz.veyq.core.moderation.ModerationTestKit.asUser
import me.raddatz.veyq.core.moderation.cases.CaseTestKit
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.SEALED_FACTS
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.SEALED_NOTE
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.case
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.decideObjection
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.lift
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.queue
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.recipients
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.take
import me.raddatz.veyq.core.moderation.hold.HoldEntity
import me.raddatz.veyq.core.moderation.hold.Holds
import me.raddatz.veyq.core.moderation.report.ReportTestKit.asMember
import me.raddatz.veyq.core.moderation.report.ReportTestKit.body
import me.raddatz.veyq.core.moderation.report.ReportTestKit.item
import me.raddatz.veyq.core.moderation.report.ReportTestKit.openCase
import me.raddatz.veyq.core.moderation.report.ReportTestKit.share
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.IDENTITY_PUBLIC_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.http.ContentType
import io.restassured.response.Response
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The statement of reasons and the objection (uc.moderation.object-to-a-measure; ADR 0093 §§ 4, 7, 14, ADR 0099 § 5)
 * with core alone: a member's folder is reported, the Server Owner — the first moderator — blocks it, the member (the
 * space's owner) reads the statement and objects, and a moderator decides. Each refusal has its control on the same
 * predicate. The mails are `StatementMailSliceTest`'s, the race of two deciders `ObjectionRacesSliceTest`'s.
 */
@QuarkusTest
class StatementsSliceTest {

    @Inject
    lateinit var operator: OperatorDetailsStore

    @Inject
    lateinit var purge: ModerationPurge

    private data class Reported(val userId: String, val scopeId: String, val folder: String, val caseId: String)

    private fun reported(): Reported {
        val m = seedMemberScope(ScopeEntity.GROUP)
        val folder = seedNode(m.scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        asMember(m.userId, m.scopeId, body(listOf(item("NODE", folder)))).then().statusCode(201)
        return Reported(m.userId, m.scopeId, folder, openCase("NODE", folder)!!.id)
    }

    /** The operating mode's column alone — the statement's form reads it; the mode's preset switches stay untouched. */
    private fun mode(value: String?) = QuarkusTransaction.requiringNew().run { operator.lockedRow().operatingMode = value }

    private fun statementsOf(userId: String): Response = asUser(userId).get("/api/statements")

    private fun object_(userId: String, statementId: String, epoch: Int = 1): Response =
        asUser(userId).contentType(ContentType.JSON).body("""{"epoch":$epoch,"sealedObjection":"c2VhbGVkLW9iamVjdGlvbg=="}""")
            .post("/api/statements/$statementId/objection")

    private fun events(caseId: String): List<ModerationEventEntity> =
        QuarkusTransaction.requiringNew().call { ModerationEventEntity.list("caseId = ?1 order by occurredAt", caseId) }

    private fun statementRow(caseId: String): ModerationStatementEntity =
        QuarkusTransaction.requiringNew().call { ModerationStatementEntity.find("caseId", caseId).firstResult()!! }

    @Test
    fun aMeasureCarriesItsStatementToEveryAffectedMemberAndAStaleRecipientListIsRefused() {
        val mod = CaseTestKit.moderator()
        val r = reported()
        val stranger = seedUser()
        // Who it reaches: on a content block, the space's owner — with the identity the browser seals the facts to.
        mod.recipients(r.caseId, "CONTENT", targetId = r.folder).then().statusCode(200)
            .body("accounts.size()", equalTo(1))
            .body("accounts[0].accountId", equalTo(r.userId))
            .body("accounts[0].identityPublicKey", equalTo(IDENTITY_PUBLIC_KEY))
            .body("guests", equalTo(0))
        mod.recipients(r.caseId, "CONTENT", targetId = UUID.randomUUID().toString()).then().statusCode(404)
            .body("code", equalTo("moderation_target_unknown"))
        // Facts sealed for someone the statement does not reach are refused, and nothing is written; the right list passes.
        mod.take(r.caseId, "CONTENT", targetId = r.folder, factsFor = listOf(stranger)).then().statusCode(409)
            .body("code", equalTo("statement_recipients_changed"))
        mod.take(r.caseId, "CONTENT", targetId = r.folder, factsFor = emptyList()).then().statusCode(409)
            .body("code", equalTo("statement_recipients_changed"))
        mod.case(r.caseId).then().body("measures.size()", equalTo(0))
        val statementId = mod.take(r.caseId, "CONTENT", targetId = r.folder).then().statusCode(201)
            .body("measures[0].statement.recipients", equalTo(1))
            .body("measures[0].statement.guests", equalTo(0))
            .body("measures[0].statement.ground", equalTo("TERMS_BREACH"))
            .body("measures[0].statement.sealedFacts", equalTo(SEALED_NOTE))
            .body("measures[0].statement.sentAt", notNullValue())
            .body("statementForm", equalTo("FULL"))
            .extract().path<String>("measures[0].statement.id")

        val mine = statementsOf(r.userId).then().statusCode(200)
            .body("statements.size()", equalTo(1))
            .body("statements[0].id", equalTo(statementId))
            .body("statements[0].caseId", equalTo(r.caseId))
            .body("statements[0].measureKind", equalTo("CONTENT"))
            .body("statements[0].targetId", equalTo(r.folder))
            .body("statements[0].scopeId", equalTo(r.scopeId))
            .body("statements[0].roles", equalTo(listOf("SCOPE_OWNER")))
            .body("statements[0].sealedFacts", equalTo(SEALED_FACTS))
            .body("statements[0].form", equalTo("FULL"))
            .body("statements[0].basis", equalTo("REPORT"))
            .body("statements[0].inForce", equalTo(true))
            .body("statements[0].canObject", equalTo(true))
            .body("statements[0].objection", nullValue())
            .extract().asString()
        assertFalse(mine.contains(mod.id), "a statement names the host, never the moderator")
        // A member it does not name is told nothing.
        statementsOf(stranger).then().statusCode(200).body("statements.size()", equalTo(0))
        // It is on the record: the event names the statement and its ground.
        val sent = events(r.caseId).single { it.event == "STATEMENT_SENT" }
        assertEquals(statementId, sent.targetId)
        assertEquals("TERMS_BREACH", sent.reason)
        assertEquals(mod.id, sent.moderatorId)

        // A blocked link reaches its creator.
        val link = share(r.userId, r.folder)
        mod.recipients(r.caseId, "SHARE", shareId = link).then().statusCode(200).body("accounts[0].accountId", equalTo(r.userId))
        mod.take(r.caseId, "SHARE", shareId = link).then().statusCode(201)
        statementsOf(r.userId).then().body("statements.find { it.measureKind == 'SHARE' }.roles", equalTo(listOf("SHARE_CREATOR")))
            .body("statements.find { it.measureKind == 'SHARE' }.shareId", equalTo(link))
    }

    @Test
    fun theFormFollowsTheOperatingModeReadWhenTheStatementIsRendered() {
        val mod = CaseTestKit.moderator()
        val before = QuarkusTransaction.requiringNew().call { OperatorDetailsEntity.findById(OperatorDetailsEntity.SINGLETON_ID)?.operatingMode }
        try {
            formFollowsTheMode(mod)
        } finally {
            mode(before)
        }
    }

    private fun formFollowsTheMode(mod: CaseTestKit.Moderator) {
        // Organisation (the mode until one is chosen): the full form, so the facts are required — and with them it passes.
        mode(null)
        val org = reported()
        mod.take(org.caseId, "CONTENT", targetId = org.folder, facts = false).then().statusCode(400)
            .body("code", equalTo("statement_facts_required"))
        mod.take(org.caseId, "CONTENT", targetId = org.folder).then().statusCode(201).body("statementForm", equalTo("FULL"))

        // Private: the reduced form, and the same measure without facts is taken.
        mode("PRIVATE")
        val priv = reported()
        mod.take(priv.caseId, "CONTENT", targetId = priv.folder, facts = false).then().statusCode(201)
            .body("statementForm", equalTo("REDUCED"))
            .body("measures[0].statement.sealedFacts", nullValue())
        statementsOf(priv.userId).then().body("statements.find { it.caseId == '${priv.caseId}' }.form", equalTo("REDUCED"))
            .body("statements.find { it.caseId == '${priv.caseId}' }.sealedFacts", nullValue())
        // Read when rendered: the same statement reads full once the mode is the organisation's again.
        mode("ORGANISATION")
        statementsOf(priv.userId).then().body("statements.find { it.caseId == '${priv.caseId}' }.form", equalTo("FULL"))
    }

    @Test
    fun anAffectedMemberObjectsOnceWhileTheMeasureStandsAndTheObjectionReopensTheCase() {
        val mod = CaseTestKit.moderator()
        val r = reported()
        val stranger = seedUser()
        val statementId = mod.take(r.caseId, "CONTENT", targetId = r.folder).then().statusCode(201)
            .extract().path<String>("measures[0].statement.id")
        // Not a recipient: an unknown statement. Another epoch: refused. The recipient: received.
        object_(stranger, statementId).then().statusCode(404).body("code", equalTo("statement_not_found"))
        object_(r.userId, statementId, epoch = 2).then().statusCode(409).body("code", equalTo("moderation_epoch_changed"))
        object_(r.userId, statementId).then().statusCode(201)
            .body("objection.mine", equalTo(true))
            .body("objection.outcome", nullValue())
            .body("canObject", equalTo(false))
        // Once per measure.
        object_(r.userId, statementId).then().statusCode(409).body("code", equalTo("objection_exists"))
        val objectionId = mod.case(r.caseId).then().statusCode(200)
            .body("objections.size()", equalTo(1))
            .body("objections[0].statementId", equalTo(statementId))
            .body("objections[0].sealedObjection", equalTo("c2VhbGVkLW9iamVjdGlvbg=="))
            .body("objections[0].mayDecide", equalTo(true))
            .extract().path<String>("objections[0].id")
        mod.queue().then().body("cases.find { it.id == '${r.caseId}' }.objectionOpen", equalTo(true))
        val received = events(r.caseId).single { it.event == "OBJECTION_RECEIVED" }
        assertEquals(r.userId, received.actorId)
        assertEquals(objectionId, received.targetId)
        assertNull(received.moderatorId)

        // A lifted measure takes no objection (control: the one in force above was taken).
        val other = reported()
        val otherStatement = mod.take(other.caseId, "CONTENT", targetId = other.folder).then().statusCode(201)
            .extract().path<String>("measures[0].statement.id")
        val measure = mod.case(other.caseId).then().extract().path<String>("measures[0].id")
        mod.lift(other.caseId, measure).then().statusCode(200)
        object_(other.userId, otherStatement).then().statusCode(409).body("code", equalTo("measure_not_in_force"))
    }

    @Test
    fun anObjectionReopensAClosedCaseWhichClosesAgainOnceItIsDecided() {
        val mod = CaseTestKit.moderator()
        val r = reported()
        val statementId = mod.take(r.caseId, "CONTENT", targetId = r.folder).then().statusCode(201)
            .extract().path<String>("measures[0].statement.id")
        // The measure's hold ends; the cleanup closes the decided case and dates its events.
        QuarkusTransaction.requiringNew().run { HoldEntity.update("expiresAt = ?1 where caseId = ?2", Instant.now().minusSeconds(1), r.caseId) }
        purge.sweep(Instant.now())
        mod.case(r.caseId).then().body("state", equalTo("CLOSED"))
        assertTrue(events(r.caseId).all { it.expiresAt != null })

        object_(r.userId, statementId).then().statusCode(201)
        mod.case(r.caseId).then().body("state", equalTo("DECIDED")).body("closedAt", nullValue())
        mod.queue().then().body("cases.find { it.id == '${r.caseId}' }.objectionOpen", equalTo(true))
        assertTrue(events(r.caseId).all { it.expiresAt == null }, "a reopened case's events are undated again")

        val objectionId = mod.case(r.caseId).then().extract().path<String>("objections[0].id")
        mod.decideObjection(r.caseId, objectionId, "UPHOLD").then().statusCode(200).body("state", equalTo("CLOSED"))
        assertTrue(events(r.caseId).all { it.expiresAt != null })
    }

    @Test
    fun theDeciderIsRefusedWhileAnotherModeratorHoldsTheKeyAndTheOtherDecides() {
        val mod = CaseTestKit.moderator()
        val deputyId = seedUser()
        appointAndAccept(mod.id, "u-$deputyId@example.com", deputyId)
        val deputy = CaseTestKit.Moderator(deputyId, mod.key)
        val r = reported()
        val statementId = mod.take(r.caseId, "CONTENT", targetId = r.folder).then().statusCode(201)
            .extract().path<String>("measures[0].statement.id")
        object_(r.userId, statementId).then().statusCode(201)
        val objectionId = mod.case(r.caseId).then().extract().path<String>("objections[0].id")

        // Accepted but not yet holding the key: the decider alone holds it, so it may decide (the control below).
        mod.case(r.caseId).then().body("objections[0].mayDecide", equalTo(true))
        // Now the deputy holds the current epoch: the decider is refused, the deputy may decide.
        QuarkusTransaction.requiringNew().run {
            ModerationGrantEntity().apply {
                id = UUID.randomUUID().toString(); moderatorId = deputyId; epoch = 1; sealedGrant = ModerationTestKit.sealedGrant(); deliveredBy = mod.id
            }.persist()
        }
        mod.case(r.caseId).then().body("objections[0].mayDecide", equalTo(false))
        mod.decideObjection(r.caseId, objectionId, "UPHOLD").then().statusCode(409)
            .body("code", equalTo("objection_needs_another_moderator"))
        deputy.case(r.caseId).then().body("objections[0].mayDecide", equalTo(true))
        deputy.decideObjection(r.caseId, objectionId, "LIFT").then().statusCode(200)
            .body("objections[0].outcome", equalTo("LIFTED"))
            .body("objections[0].ownReview", equalTo(false))
            .body("objections[0].decidedBy", equalTo(deputyId))
            .body("measures[0].liftedAt", notNullValue())
            .body("state", equalTo("CLOSED"))
        deputy.decideObjection(r.caseId, objectionId, "UPHOLD").then().statusCode(409).body("code", equalTo("objection_decided"))
        // The log records the reviewer, and the measure's own events are on it.
        val log = events(r.caseId)
        val decided = log.single { it.event == "OBJECTION_DECIDED" }
        assertEquals(deputyId, decided.moderatorId)
        assertEquals("LIFTED", decided.reason)
        assertTrue(log.none { it.event == "OBJECTION_SELF_REVIEWED" })
        assertEquals(mod.id, log.single { it.event == "MEASURE_TAKEN" }.moderatorId)
        assertEquals(deputyId, log.single { it.event == "MEASURE_LIFTED" }.moderatorId)
        // The objector reads the answer.
        statementsOf(r.userId).then()
            .body("statements[0].objection.outcome", equalTo("LIFTED"))
            .body("statements[0].objection.ownReview", equalTo(false))
            .body("statements[0].objection.decidedAt", notNullValue())
            .body("statements[0].inForce", equalTo(false))
    }

    @Test
    fun aSingleModeratorReviewsTheObjectionAsTheirOwnAndTheAnswerSaysSo() {
        val mod = CaseTestKit.moderator()
        val r = reported()
        val statementId = mod.take(r.caseId, "CONTENT", targetId = r.folder).then().statusCode(201)
            .extract().path<String>("measures[0].statement.id")
        object_(r.userId, statementId).then().statusCode(201)
        val objectionId = mod.case(r.caseId).then().extract().path<String>("objections[0].id")
        mod.decideObjection(r.caseId, objectionId, "UPHOLD").then().statusCode(200)
            .body("objections[0].outcome", equalTo("UPHELD"))
            .body("objections[0].ownReview", equalTo(true))
            .body("state", equalTo("DECIDED"))
        val log = events(r.caseId)
        assertEquals(mod.id, log.single { it.event == "OBJECTION_SELF_REVIEWED" }.moderatorId)
        assertTrue(log.none { it.event == "OBJECTION_DECIDED" })
        statementsOf(r.userId).then()
            .body("statements[0].objection.outcome", equalTo("UPHELD"))
            .body("statements[0].objection.ownReview", equalTo(true))
            .body("statements[0].inForce", equalTo(true))
        mod.queue().then().body("cases.find { it.id == '${r.caseId}' }.objectionOpen", equalTo(false))
    }

    @Test
    fun theHoldCountsFromTheStatementAndFromTheDecisionWhereNobodyCanBeTold() {
        val mod = CaseTestKit.moderator()
        val r = reported()
        mod.take(r.caseId, "CONTENT", targetId = r.folder).then().statusCode(201)
        val sentAt = statementRow(r.caseId).sentAt!!
        assertEquals(sentAt.plus(Holds.SIX_MONTHS), CaseTestKit.holdOf(r.caseId)!!.expiresAt)

        // Nobody to tell: the space has no owner left whose account could read it.
        val lone = reported()
        QuarkusTransaction.requiringNew().run { MembershipEntity.delete("scopeId", lone.scopeId) }
        mod.recipients(lone.caseId, "CONTENT", targetId = lone.folder).then().statusCode(200).body("accounts.size()", equalTo(0))
        mod.take(lone.caseId, "CONTENT", targetId = lone.folder, factsFor = emptyList()).then().statusCode(201)
            .body("measures[0].statement.sentAt", nullValue())
            .body("measures[0].statement.recipients", equalTo(0))
        val decidedAt = CaseTestKit.caseRow(lone.caseId).decidedAt!!
        assertNull(statementRow(lone.caseId).sentAt)
        assertEquals(decidedAt.plus(Holds.SIX_MONTHS), CaseTestKit.holdOf(lone.caseId)!!.expiresAt)
        assertTrue(events(lone.caseId).none { it.event == "STATEMENT_SENT" })
        assertTrue(CaseTestKit.holdOf(lone.caseId)!!.expiresAt.isAfter(Instant.now().plus(180, ChronoUnit.DAYS)))
    }
}
