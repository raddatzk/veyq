package me.raddatz.veyq.core.moderation.statement

import me.raddatz.veyq.core.admin.operator.OperatorDetailsEntity
import me.raddatz.veyq.core.admin.operator.OperatorDetailsStore
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.moderation.cases.CaseTestKit
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.recipients
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.take
import me.raddatz.veyq.core.moderation.report.ReportTestKit.asMember
import me.raddatz.veyq.core.moderation.report.ReportTestKit.body
import me.raddatz.veyq.core.moderation.report.ReportTestKit.item
import me.raddatz.veyq.core.moderation.report.ReportTestKit.openCase
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.ContractBounds
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.GRANT
import me.raddatz.veyq.core.testing.TestFixtures.newUser
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * A statement reaches every affected member, and the list the moderator's browser seals the facts to is whole or
 * refused — never cut (inbox #498, ADR 0093 § 7). The browser seals one copy per member and sends them all in the one
 * measure request, which must name exactly the server's members, so the list and `factsFor` carry one bound,
 * [StatementRecipients.MAX_SEALED]: what one request body holds at the smallest sealed facts.
 *
 * The boundary the old cap broke: 1,001 owners of the reported space. Red when the bound goes back to 1,000 — the
 * recipients answer no longer fits its own schema (`ContractBounds`) and the request's `@Size` refuses the 1,001 facts.
 * Past the bound, a step with facts is refused by name, and the same step without facts still tells every member.
 */
@QuarkusTest
class StatementRecipientsBoundSliceTest {

    @Inject
    lateinit var operator: OperatorDetailsStore

    private data class Reported(val scopeId: String, val folder: String, val caseId: String)

    /** A reported folder in a space of [owners] owners (its reporter one of them). */
    private fun reported(owners: Int): Reported {
        val m = seedMemberScope(ScopeEntity.GROUP)
        val folder = seedNode(m.scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        QuarkusTransaction.requiringNew().run {
            repeat(owners - 1) {
                val user = newUser().apply { persist() }
                MembershipEntity().apply {
                    id = UUID.randomUUID().toString(); userId = user.id; tenantId = m.tenantId; scopeId = m.scopeId
                    role = MembershipEntity.OWNER; wrappedGrant = GRANT; createdAt = Instant.now()
                }.persist()
            }
        }
        asMember(m.userId, m.scopeId, body(listOf(item("NODE", folder)))).then().statusCode(201)
        return Reported(m.scopeId, folder, openCase("NODE", folder)!!.id)
    }

    private fun told(statementId: String): Pair<Long, Long> = QuarkusTransaction.requiringNew().call {
        StatementRecipientEntity.count("statementId", statementId) to
            StatementRecipientEntity.count("statementId = ?1 and sealedFacts is not null", statementId)
    }

    @Test
    fun aStatementPastTheOldCapReachesEveryMemberWithTheFactsSealedToEach() {
        val mod = CaseTestKit.moderator()
        val r = reported(owners = 1001)

        val listed = mod.recipients(r.caseId, "CONTENT", targetId = r.folder).then().statusCode(200)
            .extract().path<List<String>>("accounts.accountId")
        assertEquals(1001, listed.size)
        assertEquals(1001, listed.toSet().size)
        ContractBounds.assertFits(SPEC, "ModerationStatementRecipients", "accounts", listed.size)

        val statementId = mod.take(r.caseId, "CONTENT", targetId = r.folder).then().statusCode(201)
            .body("measures[0].statement.recipients", equalTo(1001))
            .extract().path<String>("measures[0].statement.id")
        assertEquals(1001L to 1001L, told(statementId), "every member is told, each with the facts sealed to them")
    }

    @Test
    fun pastTheBoundAStepWithFactsIsRefusedByNameAndWithoutFactsStillTellsEveryone() {
        val mod = CaseTestKit.moderator()
        val over = StatementRecipients.MAX_SEALED + 1
        val r = reported(owners = over)
        assertEquals(over.toLong(), QuarkusTransaction.requiringNew().call { MembershipEntity.count("scopeId = ?1 and role = ?2", r.scopeId, MembershipEntity.OWNER) })

        mod.recipients(r.caseId, "CONTENT", targetId = r.folder).then().statusCode(422)
            .body("code", equalTo("statement_recipients_too_many"))
        mod.take(r.caseId, "CONTENT", targetId = r.folder).then().statusCode(422)
            .body("code", equalTo("statement_recipients_too_many"))
        // The wall this leaves: the organisation's full form needs the facts, so there no step is taken at all …
        mod.take(r.caseId, "CONTENT", targetId = r.folder, facts = false).then().statusCode(400)
            .body("code", equalTo("statement_facts_required"))
        // … and in the private form the step without facts still tells every member.
        val before = QuarkusTransaction.requiringNew().call { OperatorDetailsEntity.findById(OperatorDetailsEntity.SINGLETON_ID)?.operatingMode }
        try {
            QuarkusTransaction.requiringNew().run { operator.lockedRow().operatingMode = "PRIVATE" }
            mod.take(r.caseId, "CONTENT", targetId = r.folder, facts = false).then().statusCode(201)
                .body("measures[0].statement.recipients", equalTo(over))
        } finally {
            QuarkusTransaction.requiringNew().run { operator.lockedRow().operatingMode = before }
        }
    }

    @Test
    fun theListAndTheFactsCarryOneBound() {
        assertEquals(StatementRecipients.MAX_SEALED, ContractBounds.maxItems(SPEC, "ModerationStatementRecipients", "accounts"))
        assertEquals(StatementRecipients.MAX_SEALED, ContractBounds.maxItems(SPEC, "ModerationStatementInput", "factsFor"))
    }

    private companion object {
        const val SPEC = "moderation.openapi.yaml"
    }
}
