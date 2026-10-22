package me.raddatz.veyq.core.moderation.cases

import me.raddatz.veyq.core.account.login.SessionFamilyEntity
import me.raddatz.veyq.core.moderation.ModerationTestKit
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.dismiss
import me.raddatz.veyq.core.moderation.hold.HoldEntity
import me.raddatz.veyq.core.moderation.report.CaseState
import me.raddatz.veyq.core.moderation.report.ModerationCaseEntity
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
import me.raddatz.veyq.core.moderation.block.ModerationBlockEntity
import me.raddatz.veyq.core.moderation.report.ReportShareEntity
import jakarta.inject.Inject
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * The decisions' races (uc.moderation.review-a-case, uc.moderation.take-a-measure), run on MariaDB by
 * `scripts/run_backend_races_on_mariadb.py`, where READ COMMITTED shows what H2 hides:
 *
 *  - a dismissal racing a report of the same item: the report locks the open case after its space (`ReportHandler.caseFor`),
 *    a decision locks the case first (`CaseDecisions`), so the report either attaches before the dismissal (which then
 *    ends the hold it may have taken) or finds the case closed and opens a new one — **never a hold alive on a closed
 *    case**, and never two open cases of one item;
 *  - a sign-in racing a sign-in block: both take the account row first (`SignInBlocks.block`, `LoginHandler.verify`), so
 *    the sign-in either issued its family before (and the block revoked it) or reads the block and is refused — **no
 *    session family of the account is left active**.
 *
 * Both force the overlap ([whileUncommitted]: the decision's writes held uncommitted, the other request sent inside the
 * window) — a barrier race of the two HTTP calls never overlapped them, and stayed green with either lock removed
 * (measured on MariaDB). Forced, the bite is one round: drop the case lock from `ReportHandler.caseFor`, or the account
 * lock from `SignInBlocks.blockIn`, and each is red.
 */
@QuarkusTest
class ModerationRacesSliceTest {

    @Inject
    lateinit var decisions: CaseDecisions

    @Inject
    lateinit var blocks: SignInBlocks

    private fun activeBlockOf(accountId: String): String =
        QuarkusTransaction.requiringNew().call { ModerationBlockEntity.find("targetId = ?1 and activeKey is not null", accountId).firstResult()!!.id }

    @Test
    fun aReportInsideADismissalOpensANewCaseAndNoHoldStaysOnTheClosedOne() {
        val mod = CaseTestKit.moderator()
        raceRounds { round ->
            val m = seedMemberScope(ScopeEntity.GROUP)
            val folder = seedNode(m.scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
            asMember(m.userId, m.scopeId, body(listOf(item("NODE", folder)))).then().statusCode(201)
            val first = openCase("NODE", folder)!!.id
            // The forced order (whileUncommitted): the dismissal's writes held uncommitted, the report sent inside that window.
            val reported = whileUncommitted(
                change = { assertEquals(DecisionOutcome.Done, decisions.dismissIn(mod.id, first, null, Instant.now())) },
                overlapping = { asMember(m.userId, m.scopeId, body(listOf(item("NODE", folder)))).then().extract().statusCode() },
            )
            assertEquals(201, reported, "round $round")
            QuarkusTransaction.requiringNew().run {
                val cases = ModerationCaseEntity.list("itemId", folder)
                assertTrue(cases.count { it.state == CaseState.OPEN.name } <= 1, "round $round: two open cases of one item")
                val closed = cases.filter { it.state == CaseState.CLOSED.name }.map { it.id }
                val now = Instant.now()
                assertEquals(0L, HoldEntity.count("caseId in ?1 and expiresAt > ?2", closed, now), "round $round: a hold alive on a closed case")
                // The report sent inside the dismissal's window is not lost onto the dismissed case: it opened a new one.
                assertEquals(1L, ReportShareEntity.count("caseId", first), "round $round: a report attached to a case being dismissed")
                assertEquals(1, cases.count { it.state == CaseState.OPEN.name }, "round $round: the report opened no new case")
            }
        }
    }

    @Test
    fun aSignInRacingASignInBlockLeavesNoSessionOfTheAccountActive() {
        val mod = CaseTestKit.moderator()
        raceRounds { round ->
            val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val email = "race-${UUID.randomUUID()}@example.com"
            val member = seedUser(email = email, authPublicKey = b64(kp.public.encoded.let { it.copyOfRange(it.size - 32, it.size) }))
            val nonce = challenge(email)
            // The forced order: the block's writes (its account lock, the row, the revoke) held uncommitted, the sign-in
            // sent inside that window — it waits for the lock and then reads the block.
            val signedIn = whileUncommitted(
                change = { assertEquals(null, blocks.blockIn(mod.id, email, null, Instant.now())) },
                overlapping = { verify(email, nonce, kp) },
            )
            assertEquals(401, signedIn, "round $round: a sign-in inside the block's window must be refused")
            // And the other order: a sign-in that won the lock first is revoked by the block that follows it.
            val second = challenge(email)
            QuarkusTransaction.requiringNew().run { blocks.unblock(mod.id, activeBlockOf(member)) }
            assertEquals(200, verify(email, second, kp), "round $round: control — the lift lets it sign in")
            assertEquals(BlockOutcome.Blocked(null), blocks.block(mod.id, email, null))
            assertEquals(
                0L,
                QuarkusTransaction.requiringNew().call { SessionFamilyEntity.count("userId = ?1 and status = ?2", member, SessionFamilyEntity.ACTIVE) },
                "round $round: a session family outlived the block",
            )
        }
    }

    private fun challenge(email: String): String =
        given().contentType(ContentType.JSON).body("""{"email":"$email"}""").`when`().post("/api/login/challenge")
            .then().statusCode(200).extract().path("nonce")

    private fun verify(email: String, nonce: String, kp: KeyPair): Int {
        val sig = Signature.getInstance("Ed25519").run { initSign(kp.private); update(Base64.getDecoder().decode(nonce)); sign() }
        return given().contentType(ContentType.JSON).body("""{"email":"$email","nonce":"$nonce","signature":"${b64(sig)}"}""")
            .`when`().post("/api/login/verify").statusCode
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
