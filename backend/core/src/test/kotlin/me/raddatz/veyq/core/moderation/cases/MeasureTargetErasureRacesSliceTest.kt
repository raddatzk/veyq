package me.raddatz.veyq.core.moderation.cases

import me.raddatz.veyq.core.content.NodeSubtreePurge
import me.raddatz.veyq.core.moderation.block.ModerationBlockEntity
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.PROVISION
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.SEALED_FACTS
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.WRAPPED_FACTS_KEY
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.SEALED_NOTE
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.take
import me.raddatz.veyq.core.moderation.report.ReportTestKit.asMember
import me.raddatz.veyq.core.moderation.report.ReportTestKit.body
import me.raddatz.veyq.core.moderation.report.ReportTestKit.item
import me.raddatz.veyq.core.moderation.report.ReportTestKit.openCase
import me.raddatz.veyq.core.moderation.statement.RecipientKind
import me.raddatz.veyq.core.moderation.statement.StatementGround
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.sharing.GuestTreeShare
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.SharingStore
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.whileUncommitted
import me.raddatz.veyq.core.testing.raceRounds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * An eraser racing a measure on its target (`MeasureTargets`), run on MariaDB by `scripts/run_backend_races_on_mariadb.py`:
 * both take the target's row first — the eraser by its delete (or a link's tombstone), the measure-taker by
 * `MeasureTargets.lockStanding` — so either the measure committed first and the eraser ends it, or the eraser did and the
 * measure ends at once. **Never a measure left in force on a target that is gone.**
 *
 * Forced ([whileUncommitted]), in both orders: the decision held uncommitted while the trash purge erases its node; the
 * purge held while the decision is sent; a link's delete held while a block of it is sent (the link's lock is the share
 * row alone, no space lock). The bite is one round: take `lockStanding`'s row lock away (a plain read) and each is red.
 */
@QuarkusTest
class MeasureTargetErasureRacesSliceTest {

    @Inject
    lateinit var decisions: CaseDecisions

    @Inject
    lateinit var nodePurge: NodeSubtreePurge

    @Inject
    lateinit var sharing: SharingStore

    private class World(val scopeId: String, val root: String, val shareId: String, val caseId: String)

    /** A reported folder with a link on it, already in the trash and due for the purge. */
    private fun world(): World {
        val m = seedMemberScope(ScopeEntity.GROUP)
        val root = seedNode(m.scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val link = GuestTreeShare.seed(m.userId, m.scopeId, root, ShareEntity.READ_WRITE)
        asMember(m.userId, m.scopeId, body(listOf(item("NODE", root)))).then().statusCode(201)
        val caseId = openCase("NODE", root)!!.id
        QuarkusTransaction.requiringNew().run { NodeEntity.findById(root)!!.deletedAt = Instant.now().minusSeconds(60) }
        return World(m.scopeId, root, link.shareId, caseId)
    }

    private fun inForceOn(targetId: String): Long =
        QuarkusTransaction.requiringNew().call { ModerationBlockEntity.count("targetId = ?1 and activeKey is not null", targetId) }

    private fun purge(w: World) = nodePurge.eraseExpiredIn(w.scopeId, Instant.now().plusSeconds(60))

    @Test
    fun aPurgeInsideAnUncommittedContentBlockEndsIt() {
        val mod = CaseTestKit.moderator()
        raceRounds { round ->
            val w = world()
            val members = run {
                decisions.recipientsOf(w.caseId, MeasureStep.CONTENT, null, w.root).first!!.map { it.first }.filter { it.kind == RecipientKind.ACCOUNT }.map { it.id }
            }
            val statement = StatementInput(
                UUID.randomUUID().toString(), StatementGround.TERMS_BREACH, SealedNote(1, SEALED_NOTE), SEALED_FACTS,
                members.associateWith { WRAPPED_FACTS_KEY }, provision = PROVISION,
            )
            // The forced order: the block's writes (its node's lock among them) held uncommitted, the purge run inside.
            whileUncommitted(
                change = {
                    val taken = decisions.takeIn(mod.id, w.caseId, MeasureStep.CONTENT, null, w.root, null, statement, Instant.now())
                    assertEquals(DecisionOutcome.Done, taken.outcome, "round $round")
                },
                overlapping = { QuarkusTransaction.requiringNew().run { purge(w) } },
            )
            assertEquals(null, QuarkusTransaction.requiringNew().call { NodeEntity.findById(w.root) }, "round $round: control, the node is erased")
            assertEquals(0L, inForceOn(w.root), "round $round: a content block outlived its node")
        }
    }

    @Test
    fun aContentBlockInsideAnUncommittedPurgeEndsAtOnce() {
        val mod = CaseTestKit.moderator()
        raceRounds { round ->
            val w = world()
            val status = whileUncommitted(
                change = { purge(w) },
                overlapping = { mod.take(w.caseId, "CONTENT", targetId = w.root).statusCode },
            )
            assertEquals(201, status, "round $round: the decision is taken")
            assertEquals(1L, QuarkusTransaction.requiringNew().call { ModerationBlockEntity.count("targetId", w.root) }, "round $round: control")
            assertEquals(0L, inForceOn(w.root), "round $round: a content block taken on an erased node stands")
        }
    }

    @Test
    fun aLinkBlockInsideAnUncommittedDeleteOfTheLinkEndsAtOnce() {
        val mod = CaseTestKit.moderator()
        raceRounds { round ->
            val w = world()
            val status = whileUncommitted(
                change = { assertEquals(true, sharing.delete(w.shareId), "round $round") },
                overlapping = { mod.take(w.caseId, "SHARE", shareId = w.shareId).statusCode },
            )
            assertEquals(201, status, "round $round: the decision is taken")
            assertEquals(1L, QuarkusTransaction.requiringNew().call { ModerationBlockEntity.count("targetId", w.shareId) }, "round $round: control")
            assertEquals(0L, inForceOn(w.shareId), "round $round: a link block taken on a deleted link stands")
        }
    }
}
