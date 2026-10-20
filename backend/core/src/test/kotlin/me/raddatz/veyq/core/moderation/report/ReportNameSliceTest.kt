package me.raddatz.veyq.core.moderation.report

import me.raddatz.veyq.core.identity.PrincipalKey
import me.raddatz.veyq.core.moderation.block.ModerationBlockEntity
import me.raddatz.veyq.core.moderation.cases.CaseTestKit
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.case
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.review
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.take
import me.raddatz.veyq.core.moderation.hold.HoldEntity
import me.raddatz.veyq.core.moderation.hold.HoldPinEntity
import me.raddatz.veyq.core.moderation.hold.HoldRowEntity
import me.raddatz.veyq.core.moderation.report.ReportTestKit.asGuest
import me.raddatz.veyq.core.moderation.report.ReportTestKit.asMember
import me.raddatz.veyq.core.moderation.report.ReportTestKit.body
import me.raddatz.veyq.core.moderation.report.ReportTestKit.item
import me.raddatz.veyq.core.moderation.report.ReportTestKit.openCase
import me.raddatz.veyq.core.moderation.report.ReportTestKit.share
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.seedAttachment
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * A node's name reported on its own (ADR 0093 §§ 1, 9; uc.moderation.report A8, cr-report-a-name-and-an-uploader): the
 * kind `NAME`, its own case, a hold of the node's one row, the review's one held name, and a `CONTENT` step on it that
 * blocks the node. Each negative has its control on the same predicate: the same folder reported whole holds what lies
 * below it, the same guest's name report of a child is received.
 */
@QuarkusTest
class ReportNameSliceTest {
    private lateinit var moderator: CaseTestKit.Moderator

    @BeforeEach
    fun epoch() {
        moderator = CaseTestKit.moderator()
    }

    @Test
    fun aNameReportIsACaseOfItsOwnAndItsHoldIsTheNodesOneRow() {
        val m = seedMemberScope(ScopeEntity.GROUP)
        val folder = seedNode(m.scopeId, "FOLDER", wrappedContentKey = CONTENT_KEY, nameEditor = PrincipalKey.User(m.userId))
        val child = seedNode(m.scopeId, "FOLDER", parentId = folder, wrappedContentKey = CONTENT_KEY)
        seedAttachment(m.scopeId, folder, "COVER", uploaderUserId = m.userId)

        asMember(m.userId, m.scopeId, body(listOf(item("NAME", folder)))).then().statusCode(201)
        val nameCase = openCase("NAME", folder)!!
        assertEquals("NAME", nameCase.itemKind)
        assertNull(openCase("NODE", folder), "a name report opens no case on the node")
        val held = heldOf(nameCase.id)
        assertEquals(listOf("veyq_node" to folder), held.filter { it.first == "veyq_node" }, "the node's one row, nothing below it")
        assertTrue(held.none { it.first == "veyq_node_attachment" || it.first == "veyq_blob" }, "no attachment, no blob: $held")
        assertEquals(0L, pinsOf(nameCase.id), "nothing pinned: a name has no stored object")

        // The control: the same folder reported whole is another case, and its hold reaches below it.
        asMember(m.userId, m.scopeId, body(listOf(item("NODE", folder)))).then().statusCode(201)
        val nodeCase = openCase("NODE", folder)!!
        assertNotEquals(nameCase.id, nodeCase.id)
        assertTrue(heldOf(nodeCase.id).contains("veyq_node" to child), "a node's hold holds its subtree")
        assertTrue(pinsOf(nodeCase.id) > 0, "and pins its objects")
    }

    @Test
    fun aGuestReportsAChildsNameButNotTheLinkRootsWhichIsTheLinks() {
        val m = seedMemberScope(ScopeEntity.GROUP)
        val shared = seedNode(m.scopeId, "FOLDER", wrappedContentKey = CONTENT_KEY)
        val inside = seedNode(m.scopeId, "FOLDER", parentId = shared, wrappedContentKey = CONTENT_KEY)
        val shareId = share(m.userId, shared)

        asGuest(shareId, body(listOf(item("NAME", shared)))).then().statusCode(404).body("code", equalTo("report_item_not_found"))
        assertNull(openCase("NAME", shared))
        // The control: a name below the root is one the guest read, under a key the link hands them.
        asGuest(shareId, body(listOf(item("NAME", inside)))).then().statusCode(201)
        assertNotNull(openCase("NAME", inside))
        // And the root itself, reported whole, is in reach.
        asGuest(shareId, body(listOf(item("NODE", shared)))).then().statusCode(201)
    }

    @Test
    fun theReviewServesTheHeldNameAloneAndContentBlocksTheNodeItNames() {
        val m = seedMemberScope(ScopeEntity.GROUP)
        val folder = seedNode(m.scopeId, "FOLDER", wrappedContentKey = CONTENT_KEY, encryptedName = "bmFtZQ==", nameEditor = PrincipalKey.User(m.userId))
        seedNode(m.scopeId, "FOLDER", parentId = folder, wrappedContentKey = CONTENT_KEY)
        asMember(m.userId, m.scopeId, body(listOf(item("NAME", folder)))).then().statusCode(201)
        val caseId = openCase("NAME", folder)!!.id

        val review = moderator.review(caseId).then().statusCode(200).extract()
        assertEquals("NAME", review.path<String>("itemKind"))
        assertEquals(listOf(folder), review.path<List<String>>("share.nodes.id"), "the one held node")
        assertEquals("bmFtZQ==", review.path<String>("share.nodes[0].encryptedName"))
        assertNull(review.path<String?>("share.nodes[0].wrappedContentKey"), "no key wrap: the report handed over the name's key alone")
        assertNull(review.path<String?>("share.nodes[0].wrappedMeta"), "no meta: it was not reported")

        // The name's last editor is its uploader (ADR 0093 § 8): the statement's recipient, a sign-in proposal's target.
        moderator.case(caseId).then().statusCode(200).body("itemKind", equalTo("NAME")).body("targets.uploaders[0].id", equalTo(m.userId))
        moderator.take(caseId, "CONTENT", targetId = folder).then().statusCode(201)
        val block = QuarkusTransaction.requiringNew().call { ModerationBlockEntity.find("caseId", caseId).firstResult() }!!
        assertEquals("CONTENT_NODE", block.kind, "a name blocks its node: the server serves no node without its name")
        assertEquals(folder, block.targetId)
    }

    private fun heldOf(caseId: String): List<Pair<String, String>> = QuarkusTransaction.requiringNew().call {
        val hold = HoldEntity.find("caseId", caseId).firstResult()!!
        HoldRowEntity.list("holdId", hold.id).map { it.sourceTable to it.sourceId }
    }

    private fun pinsOf(caseId: String): Long = QuarkusTransaction.requiringNew().call {
        HoldPinEntity.count("holdId", HoldEntity.find("caseId", caseId).firstResult()!!.id)
    }
}
