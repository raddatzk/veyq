package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.identity.PrincipalKey
import me.raddatz.veyq.core.moderation.block.ModerationBlockEntity
import me.raddatz.veyq.core.moderation.cases.CaseDecisions
import me.raddatz.veyq.core.moderation.cases.CaseTestKit
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.PROVISION
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.SEALED_FACTS
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.SEALED_NOTE
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.WRAPPED_FACTS_KEY
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.take
import me.raddatz.veyq.core.moderation.cases.DecisionOutcome
import me.raddatz.veyq.core.moderation.cases.MeasureStep
import me.raddatz.veyq.core.moderation.cases.SealedNote
import me.raddatz.veyq.core.moderation.cases.StatementInput
import me.raddatz.veyq.core.moderation.report.ReportTestKit.asMember
import me.raddatz.veyq.core.moderation.report.ReportTestKit.body
import me.raddatz.veyq.core.moderation.report.ReportTestKit.item
import me.raddatz.veyq.core.moderation.report.ReportTestKit.openCase
import me.raddatz.veyq.core.moderation.statement.RecipientKind
import me.raddatz.veyq.core.moderation.statement.StatementGround
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.whileUncommitted
import me.raddatz.veyq.core.testing.raceRounds
import me.raddatz.veyq.memories.banner.BannerKit
import me.raddatz.veyq.memories.banner.MemoryBannerOutcome
import me.raddatz.veyq.memories.banner.MemoryBannerStore
import me.raddatz.veyq.memories.media.SharedObjectKit.medium
import me.raddatz.veyq.memories.media.SharedObjectKit.setBanner
import me.raddatz.veyq.memories.media.SharedObjectKit.shared
import me.raddatz.veyq.memories.media.SharedObjectKit.trashAndPurge
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * A measure taken on an erased medium racing the removal of the banner that still holds the medium's objects (owner
 * 2026-10-19, ADR 0093 § 6; `MeasureTargets.survivingObjects`), run on MariaDB by `scripts/run_backend_races_on_mariadb.py`:
 * the taker locks the rows on the surviving objects, the banner's removal locks them to delete them
 * (`BlobUploadStore.deleteBlobRow`), both in the one lock order (`StoredObjectLocks`). So either the measure committed
 * first and the removal of the objects' last rows ends it (`MeasureTargets.objectsGone`), or the removal did and the measure finds no
 * object surviving and ends at once. **Never a measure left in force on objects that are gone.**
 *
 * Forced ([whileUncommitted]), in both orders: the decision held uncommitted while the banner and its poster are
 * removed; the removal held uncommitted while the decision is sent. Bite (run 2026-10-19, then restored): the rows'
 * lock taken out of `survivingObjects` (a plain read) reddens both in round 0 - unlocked, the measure neither holds the
 * removal back until it is committed (the first) nor waits for the removal's commit (the second), and hangs on objects
 * that are gone. `MediaLockOrderRaceSliceTest` forces the crosswise order.
 */
@QuarkusTest
class MeasureOnSurvivingObjectRacesSliceTest {

    @Inject
    lateinit var decisions: CaseDecisions

    @Inject
    lateinit var banners: MemoryBannerStore

    /** A reported medium, erased, whose objects the Memory's banner and its poster still hold. */
    private class World(
        val userId: String, val scopeId: String, val memory: String, val mediaId: String, val caseId: String,
        val banner: String, val poster: String,
    )

    private fun world(): World {
        val (userId, scopeId) = seedMemberScope(ScopeEntity.GROUP)
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val m = medium(userId, scopeId, memory)
        val banner = UUID.randomUUID().toString()
        val poster = UUID.randomUUID().toString()
        setBanner(userId, scopeId, memory, shared(m.original.blobId, banner), shared(m.preview.blobId, poster)).statusCode(204)
        asMember(userId, scopeId, body(listOf(item("ITEM", m.mediaId)))).then().statusCode(201)
        val caseId = openCase("ITEM", m.mediaId)!!.id
        trashAndPurge(userId, scopeId, m.mediaId)
        return World(userId, scopeId, memory, m.mediaId, caseId, banner, poster)
    }

    private fun measuresOn(targetId: String): Long =
        QuarkusTransaction.requiringNew().call { ModerationBlockEntity.count("targetId", targetId) }

    private fun inForceOn(targetId: String): Long =
        QuarkusTransaction.requiringNew().call { ModerationBlockEntity.count("targetId = ?1 and activeKey is not null", targetId) }

    private fun removeBanner(w: World): MemoryBannerOutcome =
        banners.remove(w.scopeId, w.memory, w.banner, w.poster, "bWV0YQ==", 1, PrincipalKey.User(w.userId))

    @Test
    fun aBannerRemovalInsideAnUncommittedMeasureEndsIt() {
        val mod = CaseTestKit.moderator()
        raceRounds { round ->
            val w = world()
            val members = decisions.recipientsOf(w.caseId, MeasureStep.CONTENT, null, w.mediaId).first!!
                .map { it.first }.filter { it.kind == RecipientKind.ACCOUNT }.map { it.id }
            val statement = StatementInput(
                UUID.randomUUID().toString(), StatementGround.TERMS_BREACH, SealedNote(1, SEALED_NOTE), SEALED_FACTS,
                members.associateWith { WRAPPED_FACTS_KEY }, provision = PROVISION,
            )
            // The forced order: the measure's writes (the surviving objects' row locks among them) held uncommitted, the
            // banner's removal run inside.
            val removed = whileUncommitted(
                change = {
                    val taken = decisions.takeIn(mod.id, w.caseId, MeasureStep.CONTENT, null, w.mediaId, null, statement, Instant.now())
                    assertEquals(DecisionOutcome.Done, taken.outcome, "round $round")
                    assertEquals(true, taken.changed?.activeKey != null, "round $round: control, the measure hangs on the banner's objects")
                },
                overlapping = { removeBanner(w) },
            )
            assertEquals(MemoryBannerOutcome.Done, removed, "round $round: the banner is removed")
            assertEquals(1L, measuresOn(w.mediaId), "round $round: control, the measure is taken")
            assertEquals(0L, inForceOn(w.mediaId), "round $round: a measure outlived the objects it hung on")
        }
    }

    @Test
    fun aMeasureInsideAnUncommittedBannerRemovalEndsAtOnce() {
        val mod = CaseTestKit.moderator()
        raceRounds { round ->
            val w = world()
            val status = whileUncommitted(
                change = { assertEquals(MemoryBannerOutcome.Done, removeBanner(w), "round $round") },
                overlapping = { mod.take(w.caseId, "CONTENT", targetId = w.mediaId).statusCode },
            )
            assertEquals(201, status, "round $round: the decision is taken")
            assertEquals(Pair(null, null), BannerKit.slots(w.memory), "round $round: control, the banner is gone")
            assertEquals(1L, measuresOn(w.mediaId), "round $round: control, the measure is taken")
            assertEquals(0L, inForceOn(w.mediaId), "round $round: a measure hangs on objects that are gone")
        }
    }
}
