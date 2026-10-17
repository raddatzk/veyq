package me.raddatz.veyq.core.moderation.report

import me.raddatz.veyq.core.moderation.report.ReportTestKit.asMember
import me.raddatz.veyq.core.moderation.report.ReportTestKit.body
import me.raddatz.veyq.core.moderation.report.ReportTestKit.epochOne
import me.raddatz.veyq.core.moderation.report.ReportTestKit.item
import me.raddatz.veyq.core.moderation.report.ReportTestKit.openCase
import me.raddatz.veyq.core.moderation.report.ReportTestKit.sharesOf
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.RACE_ROUNDS
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.race
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Two reports of one item at once (uc.moderation.report, "one case per item"): the item's space row is the report's
 * lock (`ReportHandler`), so the second finds the first's open case instead of colliding on the unique `open_key` — a
 * 500 that would lose a report. Run on MariaDB by `scripts/run_backend_races_on_mariadb.py`, where READ COMMITTED shows
 * what H2 hides.
 */
@QuarkusTest
class ReportRaceSliceTest {

    @Test
    fun twoReportsOfOneItemAtOnceAreOneCaseWithTwoReports() {
        epochOne()
        repeat(RACE_ROUNDS) { round ->
            val m = seedMemberScope(ScopeEntity.GROUP)
            val folder = seedNode(m.scopeId, "FOLDER", wrappedContentKey = CONTENT_KEY)
            val statuses = race(
                { asMember(m.userId, m.scopeId, body(listOf(item("NODE", folder)))).then().extract().statusCode() },
                { asMember(m.userId, m.scopeId, body(listOf(item("NODE", folder)))).then().extract().statusCode() },
            )
            assertEquals(listOf(201, 201), statuses, "round $round")
            val case = openCase("NODE", folder)!!
            assertEquals(1L, QuarkusTransaction.requiringNew().call { ModerationCaseEntity.count("itemId", folder) }, "round $round")
            assertEquals(2, sharesOf(case.id).size, "round $round")
        }
    }
}
