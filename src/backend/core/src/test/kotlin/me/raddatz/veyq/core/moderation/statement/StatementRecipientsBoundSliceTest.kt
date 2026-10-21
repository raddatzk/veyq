package me.raddatz.veyq.core.moderation.statement

import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.moderation.cases.CaseTestKit
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.call
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
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * A statement reaches every affected member, and the list the moderator's browser wraps the facts' key to is whole or
 * refused — never cut (inbox #498, ADR 0093 § 7). The browser seals the facts once and wraps only `K_facts` per member,
 * sending every wrap in the one measure request, which must name exactly the server's members, so the list and
 * `factsKeyFor` carry one bound, [StatementRecipients.MAX_SEALED]: what one request body holds at one wrap a member,
 * whatever the facts' length.
 *
 * The bound is measured, not recalled: [aBodyAtTheBoundWithEveryFieldAtItsLargestFitsTheRequestLimit] sends the largest
 * body the contract lets a step have at the bound and the server reads it; red when the bound grows past what Quarkus'
 * 10,240 KiB request limit holds: the server refuses the body on its `Content-Length` (`413 request_too_large`,
 * `RequestTooLargeRoutes`) and closes the connection, which a client still uploading sees as a reset — measured at
 * 6,400 (`SocketException: Connection reset`). Past the bound, a step is refused by name: every statement carries the
 * facts (ADR 0093 § 7, one form on every instance), so none is taken there.
 */
@QuarkusTest
class StatementRecipientsBoundSliceTest {

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
            StatementRecipientEntity.count("statementId = ?1 and wrappedFactsKey is not null", statementId)
    }

    @Test
    fun aStatementPastTheOldCapReachesEveryMemberWithTheFactsKeyWrappedToEach() {
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
        assertEquals(1001L to 1001L, told(statementId), "every member is told, each with the facts' key wrapped to them")
        val once = QuarkusTransaction.requiringNew().call { ModerationStatementEntity.findById(statementId)!!.sealedMemberFacts }
        assertEquals(CaseTestKit.SEALED_FACTS, once, "the facts themselves are stored once, on the statement")
    }

    @Test
    fun pastTheBoundAStepIsRefusedByName() {
        val mod = CaseTestKit.moderator()
        val over = StatementRecipients.MAX_SEALED + 1
        val r = reported(owners = over)
        assertEquals(over.toLong(), QuarkusTransaction.requiringNew().call { MembershipEntity.count("scopeId = ?1 and role = ?2", r.scopeId, MembershipEntity.OWNER) })

        mod.recipients(r.caseId, "CONTENT", targetId = r.folder).then().statusCode(422)
            .body("code", equalTo("statement_recipients_too_many"))
        mod.take(r.caseId, "CONTENT", targetId = r.folder).then().statusCode(422)
            .body("code", equalTo("statement_recipients_too_many"))
        // The wall this leaves: every statement needs the facts (ADR 0093 § 7, one form), so past it no step is taken at all.
        mod.take(r.caseId, "CONTENT", targetId = r.folder, facts = false).then().statusCode(400)
            .body("code", equalTo("statement_facts_required"))
    }

    /**
     * The largest `takeModerationMeasure` body the contract allows at [StatementRecipients.MAX_SEALED] wraps — every wrap,
     * the note, the log's facts, the facts sealed once, a deferral's reason and the provision at their `maxLength`, and
     * the most reporter notices at the longest address — reaches the handler, which answers for its content (a list
     * naming other members than the server's: `409 statement_recipients_changed`), not for its size. Each wrap's entry
     * is measured at 1,650 bytes.
     */
    @Test
    fun aBodyAtTheBoundWithEveryFieldAtItsLargestFitsTheRequestLimit() {
        val mod = CaseTestKit.moderator()
        val r = reported(owners = 1)
        val sealed = { schema: String, property: String -> "A".repeat(ContractBounds.maxLength(SPEC, schema, property)!!) }
        val note = """{"epoch":1,"sealedNote":"${sealed("SealedModerationNote", "sealedNote")}"}"""
        val wrap = sealed("WrappedFactsKey", "wrappedFactsKey")
        val entry = { id: UUID -> """{"accountId":"$id","wrappedFactsKey":"$wrap"},""" }
        assertEquals(1650, entry(UUID.randomUUID()).toByteArray().size, "one member's share of the body")
        val wraps = (1..StatementRecipients.MAX_SEALED).joinToString("") { entry(UUID.randomUUID()) }.dropLast(1)
        // The longest address @Email takes: a 64-character local part, labels of at most 63, 254 characters in all.
        val address = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(58) + ".de"
        assertEquals(254, address.length)
        val notices = (1..ContractBounds.maxItems(SPEC, "ModerationMeasureRequest", "reporterNotices")!!)
            .joinToString(",") { """{"reportId":"${UUID.randomUUID()}","address":"$address"}""" }
        val until = Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
        val statement = """{"statementId":"${UUID.randomUUID()}","ground":"TERMS_BREACH","provision":"${"p".repeat(200)}",""" +
            """"facts":$note,"sealedFacts":"${sealed("ModerationStatementInput", "sealedFacts")}","factsKeyFor":[$wraps],""" +
            """"deferral":{"until":"$until","reason":$note}}"""
        val body = """{"kind":"CONTENT","targetId":"${r.folder}","note":$note,"statement":$statement,"reporterNotices":[$notices]}"""
        mod.call("takeModerationMeasure", r.caseId, "POST", "/api/moderation/cases/${r.caseId}/measures", body)
            .then().statusCode(409).body("code", equalTo("statement_recipients_changed"))
        val size = body.toByteArray().size
        assertTrue(size <= REQUEST_LIMIT, "the body is $size bytes, past the server's $REQUEST_LIMIT")
    }

    @Test
    fun theListAndTheFactsKeysCarryOneBound() {
        assertEquals(StatementRecipients.MAX_SEALED, ContractBounds.maxItems(SPEC, "ModerationStatementRecipients", "accounts"))
        assertEquals(StatementRecipients.MAX_SEALED, ContractBounds.maxItems(SPEC, "ModerationStatementInput", "factsKeyFor"))
    }

    private companion object {
        const val SPEC = "moderation.openapi.yaml"

        /** `quarkus.http.limits.max-body-size` is unset: Quarkus' default, 10,240 KiB. */
        const val REQUEST_LIMIT = 10_240 * 1024
    }
}
