package me.raddatz.veyq.expenses.moderation

import me.raddatz.veyq.core.moderation.cases.CaseTestKit
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.call
import me.raddatz.veyq.core.moderation.hold.HoldRowEntity
import me.raddatz.veyq.core.moderation.report.ReportTestKit.asMember
import me.raddatz.veyq.core.moderation.report.ReportTestKit.body
import me.raddatz.veyq.core.moderation.report.ReportTestKit.item
import me.raddatz.veyq.core.moderation.report.ReportTestKit.openCase
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.expenses.ExpensesNodeKinds
import me.raddatz.veyq.expenses.pot.PotRevisionEntity
import me.raddatz.veyq.expenses.pot.PotWire.Rev
import me.raddatz.veyq.expenses.pot.PotWire.append
import me.raddatz.veyq.expenses.pot.PotWire.body
import me.raddatz.veyq.expenses.pot.PotWire.erase
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.Matchers.containsInAnyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * A Pot's entry as a reported item (uc.moderation.report, CR decision 7): the report reaches the whole entry, its hold
 * copies every revision of it and nothing of another entry, and what the hold copied outlives the participant's erasure
 * and the Pot's purge — the moderator's review reads it from the snapshot, without a writer.
 */
@QuarkusTest
class PotModerationSliceTest {

    @Test
    fun aReportedEntrysHoldCopiesEveryRevisionAndOutlivesTheErasure() {
        val mod = CaseTestKit.moderator()
        val m = seedMemberScope(ScopeEntity.GROUP)
        val pot = seedNode(m.scopeId, ExpensesNodeKinds.POT, wrappedContentKey = CONTENT_KEY)
        val person = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        append(m.userId, m.scopeId, pot, listOf(Rev(person, body = body('P')), Rev(other))).statusCode(204)
        append(m.userId, m.scopeId, pot, listOf(Rev(person, 2, body = body('Q')))).statusCode(204)

        asMember(m.userId, m.scopeId, body(listOf(item("ITEM", person)))).then().statusCode(201)
        val caseId = openCase("ITEM", person)!!.id
        val held = heldRevisions()
        val personRows = QuarkusTransaction.requiringNew().call { PotRevisionEntity.list("entryId", person).map { it.id }.toSet() }
        assertEquals(personRows, held.toSet(), "every revision of the reported entry, and none of another")

        erase(m.userId, m.scopeId, pot, person, 3).statusCode(204)
        assertEquals(held.toSet(), heldRevisions().toSet(), "the erasure leaves the hold's copies")
        given().header("Authorization", bearer(m.userId)).`when`().delete("/api/scopes/${m.scopeId}/nodes/$pot").then().statusCode(204)

        mod.call("listCasePotRevisions", caseId, "GET", "/api/moderation/cases/$caseId/pots").then().statusCode(200)
            .body("revisions.size()", equalTo(2))
            .body("revisions.revision", containsInAnyOrder(1, 2))
            .body("revisions.sealedBody", containsInAnyOrder(body('P'), body('Q')))
            .body("revisions[0].nodeId", equalTo(pot)).body("revisions[0].entryId", equalTo(person))
            .body("revisions[0].containsKey('writer')", equalTo(false))
    }

    @Test
    fun anIdNoPotHoldsIsNoReportableItem() {
        CaseTestKit.moderator()
        val m = seedMemberScope(ScopeEntity.GROUP)
        // an id no Pot holds is no reportable item: refused as such, beside the control above
        asMember(m.userId, m.scopeId, body(listOf(item("ITEM", UUID.randomUUID().toString())))).then().statusCode(404)
    }

    private fun heldRevisions(): List<String> =
        QuarkusTransaction.requiringNew().call { HoldRowEntity.list("sourceTable", HeldRevisions.TABLE).map { it.sourceId } }
}
