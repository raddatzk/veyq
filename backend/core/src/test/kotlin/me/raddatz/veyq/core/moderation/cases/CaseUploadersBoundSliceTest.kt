package me.raddatz.veyq.core.moderation.cases

import me.raddatz.veyq.core.moderation.cases.CaseTestKit.case
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.export
import me.raddatz.veyq.core.moderation.hold.HoldEntity
import me.raddatz.veyq.core.moderation.hold.HoldRowEntity
import me.raddatz.veyq.core.moderation.report.ReportTestKit.asMember
import me.raddatz.veyq.core.moderation.report.ReportTestKit.body
import me.raddatz.veyq.core.moderation.report.ReportTestKit.item
import me.raddatz.veyq.core.moderation.report.ReportTestKit.openCase
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.ContractBounds
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The uploaders a case names and the uploaders its Art. 18 export carries are one list (`known.uploaders`), so they
 * carry one bound — none: nothing counts the members and guest identities a held subtree names, and the export must
 * carry all relevant information (ADR 0093 § 13, inbox #499). The boundary the export's old cap broke: 1,001 guest
 * uploaders held. Red when either schema caps the list below what the server answers (`ContractBounds`), or when the
 * export drops one.
 */
@QuarkusTest
class CaseUploadersBoundSliceTest {

    @Test
    fun aCaseOfMoreThanAThousandUploadersShowsAndExportsEveryOne() {
        val mod = CaseTestKit.moderator()
        val m = seedMemberScope(ScopeEntity.GROUP)
        val folder = seedNode(m.scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        asMember(m.userId, m.scopeId, body(listOf(item("NODE", folder)))).then().statusCode(201)
        val caseId = openCase("NODE", folder)!!.id

        val guests = (1..UPLOADERS).map { UUID.randomUUID().toString() }.toSet()
        QuarkusTransaction.requiringNew().run {
            val hold = HoldEntity.find("caseId = ?1 and expiresAt > ?2", caseId, Instant.now()).firstResult()
            assertNotNull(hold, "a reported item's case holds its rows from the report on")
            guests.forEach { identity ->
                HoldRowEntity().apply {
                    id = UUID.randomUUID().toString(); holdId = hold!!.id; sourceTable = "veyq_share_identity"; sourceId = identity
                    payload = """{"id":"$identity","share_id":"$SHARE"}"""
                }.persist()
            }
        }

        val shown = mod.case(caseId).then().statusCode(200).extract().path<List<String>>("targets.uploaders.id")
        val exported = mod.export(caseId).then().statusCode(200).extract().path<List<String>>("uploaders.id")

        assertEquals(guests, shown.toSet() intersect guests, "the case names every held uploader")
        assertEquals(shown.toSet(), exported.toSet(), "the export carries exactly the uploaders the case names")
        assertEquals(shown.size, exported.size)
        ContractBounds.assertFits(SPEC, "ModerationTargets", "uploaders", shown.size)
        ContractBounds.assertFits(SPEC, "ModerationCaseExport", "uploaders", exported.size)
    }

    @Test
    fun theCaseAndTheExportCarryOneBound() {
        assertEquals(
            ContractBounds.maxItems(SPEC, "ModerationTargets", "uploaders"),
            ContractBounds.maxItems(SPEC, "ModerationCaseExport", "uploaders"),
        )
    }

    private companion object {
        const val SPEC = "moderation.openapi.yaml"
        const val UPLOADERS = 1001
        const val SHARE = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    }
}
