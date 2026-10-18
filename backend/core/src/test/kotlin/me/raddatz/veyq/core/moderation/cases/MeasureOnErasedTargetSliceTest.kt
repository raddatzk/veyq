package me.raddatz.veyq.core.moderation.cases

import me.raddatz.veyq.core.content.NodeSubtreePurge
import me.raddatz.veyq.core.moderation.ModerationEventEntity
import me.raddatz.veyq.core.moderation.ModerationLog
import me.raddatz.veyq.core.moderation.ModerationPurge
import me.raddatz.veyq.core.moderation.block.BlockKind
import me.raddatz.veyq.core.moderation.block.ModerationBlockEntity
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.bar
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.order
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.orderBody
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.take
import me.raddatz.veyq.core.moderation.report.ModerationCaseEntity
import me.raddatz.veyq.core.moderation.report.ReportShareEntity
import me.raddatz.veyq.core.moderation.report.ReportTestKit.asGuest
import me.raddatz.veyq.core.moderation.report.ReportTestKit.body
import me.raddatz.veyq.core.moderation.report.ReportTestKit.item
import me.raddatz.veyq.core.moderation.report.ReportTestKit.openCase
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.sharing.GuestTreeShare
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.ShareIdentityEntity
import me.raddatz.veyq.core.spaces.scope.ScopePurge
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * **A measure never stands on an erased target** (GDPR Art. 5(1)(e); ADR 0093 §§ 6, 11; `MeasureTargets`): every eraser of
 * a link, a node, a guest identity — and, in `ModerationMemoriesSliceTest`, a medium — ends the measures on it in its own
 * transaction, lifted by no one and logged on the case, so the case's records run to their date. One test per eraser
 * path, each with its control (the target still standing, its measure still in force); the decision's half — a measure
 * taken on a target already gone ends at once — last. The races are `MeasureTargetErasureRacesSliceTest`'s.
 */
@QuarkusTest
class MeasureOnErasedTargetSliceTest {

    @Inject
    lateinit var nodePurge: NodeSubtreePurge

    @Inject
    lateinit var scopePurge: ScopePurge

    @Inject
    lateinit var moderationPurge: ModerationPurge

    /** A group space: a folder with a guest link on it, the guest's own folder below it, and a case its guest reported. */
    private class World(
        val owner: String, val scopeId: String, val root: String, val guestFolder: String, val link: GuestTreeShare.Link,
        val identityId: String, val caseId: String, val reportId: String,
    )

    private fun world(): World {
        val m = seedMemberScope(ScopeEntity.GROUP)
        val root = seedNode(m.scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val link = GuestTreeShare.seed(m.userId, m.scopeId, root, ShareEntity.READ_WRITE)
        val identityId = QuarkusTransaction.requiringNew().call { ShareIdentityEntity.find("shareId", link.shareId).firstResult()!!.id }
        val guestFolder = UUID.randomUUID().toString()
        GuestTreeShare.create(link, root, NodeEntity.FOLDER, nodeId = guestFolder).statusCode(201)
        asGuest(link.shareId, body(listOf(item("NODE", root)))).then().statusCode(201)
        val caseId = openCase("NODE", root)!!.id
        val reportId = QuarkusTransaction.requiringNew().call { ReportShareEntity.find("caseId", caseId).firstResult()!!.reportId }
        return World(m.userId, m.scopeId, root, guestFolder, link, identityId, caseId, reportId)
    }

    private fun measure(r: io.restassured.response.Response): String =
        r.then().statusCode(201).extract().path<List<String>>("measures.id").last()

    private fun row(id: String): ModerationBlockEntity = QuarkusTransaction.requiringNew().call { ModerationBlockEntity.findById(id)!! }

    private fun barOf(w: World): String {
        mod.bar(w.caseId, w.reportId).then().statusCode(201)
        return QuarkusTransaction.requiringNew().call {
            ModerationBlockEntity.find("caseId = ?1 and kind = ?2", w.caseId, BlockKind.REPORTER_LINK.name).firstResult()!!.id
        }
    }

    private fun assertStands(id: String, why: String) = assertNotNull(row(id).activeKey, why)

    /** Ended by its target's erasure: no longer in force, lifted at a time by no one, and the end logged on its case. */
    private fun assertEndedByErasure(id: String, caseId: String) {
        val r = row(id)
        assertNull(r.activeKey, "${r.kind} on ${r.targetId}: still in force on an erased target")
        assertNotNull(r.liftedAt)
        assertNull(r.liftedBy, "${r.kind}: lifted by no one")
        assertEquals(
            1L,
            QuarkusTransaction.requiringNew().call {
                ModerationEventEntity.count(
                    "caseId = ?1 and event = ?2 and reason = ?3 and targetId = ?4 and moderatorId is null",
                    caseId, "MEASURE_LIFTED", r.kind, r.targetId,
                )
            },
            "${r.kind}: its end is logged on the case",
        )
    }

    private fun trash(scopeId: String, vararg nodeIds: String, at: Instant = Instant.now().minusSeconds(60)) =
        QuarkusTransaction.requiringNew().run { nodeIds.forEach { NodeEntity.findById(it)!!.deletedAt = at } }

    private fun purgeTrash(scopeId: String, cutoff: Instant = Instant.now().plusSeconds(60)) =
        QuarkusTransaction.requiringNew().run { nodePurge.eraseExpiredIn(scopeId, cutoff) }

    private lateinit var mod: CaseTestKit.Moderator

    /**
     * The trash purge (`NodeSubtreePurge.erase`) ends the block on the node it erases, and — through the link's tombstone and
     * the identity's drop it makes — the link's and the guest uploader's; the case then closes with its hold and its records
     * go at their date, where a measure in force kept them however late.
     */
    @Test
    fun theTrashPurgeEndsTheMeasuresOnWhatItErasesAndTheCaseThenGoesAtItsDate() {
        mod = CaseTestKit.moderator()
        val w = world()
        val content = measure(mod.take(w.caseId, "CONTENT", targetId = w.root))
        val link = measure(mod.take(w.caseId, "SHARE", shareId = w.link.shareId))
        val uploader = measure(mod.take(w.caseId, "GUEST_IDENTITY", targetId = w.identityId))
        trash(w.scopeId, w.root, w.guestFolder, at = Instant.now())
        purgeTrash(w.scopeId, cutoff = Instant.now().minus(Duration.ofDays(1)))
        listOf(content, link, uploader).forEach { assertStands(it, "control: in the trash, not yet purged, every measure stands") }
        // Control: while they stand, nothing of the case goes, however late — its hold ends and it closes, nothing more.
        val late = Instant.now().plus(Duration.ofDays(5 * 366))
        moderationPurge.sweep(late)
        val closedAt = CaseTestKit.caseRow(w.caseId).closedAt!!
        moderationPurge.sweep(ModerationLog.retentionEnd(closedAt).plus(Duration.ofDays(1)))
        assertNotNull(QuarkusTransaction.requiringNew().call { ModerationCaseEntity.findById(w.caseId) }, "control: held by its measures")

        purgeTrash(w.scopeId)
        assertNull(QuarkusTransaction.requiringNew().call { NodeEntity.findById(w.root) }, "the subtree is erased")
        listOf(content, link, uploader).forEach { assertEndedByErasure(it, w.caseId) }
        moderationPurge.sweep(ModerationLog.retentionEnd(closedAt).plus(Duration.ofDays(1)))
        assertNull(QuarkusTransaction.requiringNew().call { ModerationCaseEntity.findById(w.caseId) }, "the case's records went at their date")
    }

    /**
     * Deleting a link (`SharingStore.tombstone`) ends its block and the bar of the guest reporter who came through it; the
     * guest uploader's block stands while the identity does (it still names the folder it uploaded), and ends when the
     * identity is dropped with that folder (`SharingStore.dropDeadIdentities`).
     */
    @Test
    fun deletingALinkEndsItsBlockAndItsReportersBarAndTheUploadersBlockEndsWithItsIdentity() {
        mod = CaseTestKit.moderator()
        val w = world()
        val link = measure(mod.take(w.caseId, "SHARE", shareId = w.link.shareId))
        val uploader = measure(mod.take(w.caseId, "GUEST_IDENTITY", targetId = w.identityId))
        val bar = barOf(w)
        given().header("Authorization", bearer(w.owner)).`when`().delete("/api/shares/${w.link.shareId}").then().statusCode(204)
        assertEndedByErasure(link, w.caseId)
        assertEndedByErasure(bar, w.caseId)
        assertNotNull(QuarkusTransaction.requiringNew().call { ShareIdentityEntity.findById(w.identityId) }, "control: the identity names its folder")
        assertStands(uploader, "control: the identity stands, and so does its block")

        trash(w.scopeId, w.guestFolder)
        purgeTrash(w.scopeId)
        assertNull(QuarkusTransaction.requiringNew().call { ShareIdentityEntity.findById(w.identityId) }, "dropped with its folder")
        assertEndedByErasure(uploader, w.caseId)
    }

    /** A space's purge ends every measure on what it erases: its nodes', its links' and its guests' (the scope erasers). */
    @Test
    fun aSpacesPurgeEndsEveryMeasureOnItsNodesLinksAndGuests() {
        mod = CaseTestKit.moderator()
        val w = world()
        val content = measure(mod.take(w.caseId, "CONTENT", targetId = w.root))
        val link = measure(mod.take(w.caseId, "SHARE", shareId = w.link.shareId))
        val uploader = measure(mod.take(w.caseId, "GUEST_IDENTITY", targetId = w.identityId))
        val bar = barOf(w)
        listOf(content, link, uploader, bar).forEach { assertStands(it, "control: in force before the purge") }
        scopePurge.purge(w.scopeId)
        listOf(content, link, uploader, bar).forEach { assertEndedByErasure(it, w.caseId) }
    }

    /**
     * The decision's half: a measure or a bar on a target already erased — here a link deleted after the report — is taken
     * and ends at once, as its eraser would have ended it (`MeasureTargets.lockStanding`); one on a target that stands, in
     * force. An authority's removal order on the deleted link blocks its content and ends the link's block alike.
     */
    @Test
    fun aMeasureTakenOnATargetAlreadyErasedEndsAtOnce() {
        mod = CaseTestKit.moderator()
        val w = world()
        given().header("Authorization", bearer(w.owner)).`when`().delete("/api/shares/${w.link.shareId}").then().statusCode(204)
        assertEndedByErasure(measure(mod.take(w.caseId, "SHARE", shareId = w.link.shareId)), w.caseId)
        assertEndedByErasure(barOf(w), w.caseId)

        val orderCase = mod.order(orderBody(w.link.shareId)).then().statusCode(201).extract().path<String>("caseId")
        val ordered = QuarkusTransaction.requiringNew().call { ModerationBlockEntity.list("caseId", orderCase) }
        assertEndedByErasure(ordered.single { it.kind == BlockKind.SHARE.name }.id, orderCase)
        assertNotNull(
            ordered.single { it.kind == BlockKind.CONTENT_NODE.name }.activeKey,
            "control: the node stands, and so does the order's block of it",
        )
    }
}
