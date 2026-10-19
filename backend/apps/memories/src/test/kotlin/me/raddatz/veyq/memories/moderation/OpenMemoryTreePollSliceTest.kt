package me.raddatz.veyq.memories.moderation

import me.raddatz.veyq.core.moderation.cases.CaseTestKit
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.lift
import me.raddatz.veyq.core.moderation.cases.CaseTestKit.take
import me.raddatz.veyq.core.moderation.report.ReportTestKit.asMember
import me.raddatz.veyq.core.moderation.report.ReportTestKit.body
import me.raddatz.veyq.core.moderation.report.ReportTestKit.item
import me.raddatz.veyq.core.moderation.report.ReportTestKit.openCase
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.memories.media.StatementCount
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.response.ValidatableResponse
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A member with a Memory open learns a **node** block — of the Memory, or of a folder above it — through the scope's tree
 * poll, which the web runs on every page of the tree, a Memory's included (inbox #503, ADR 0081). A node block bumps the
 * scope's tree revision and no item revision (`CaseDecisions.announce`), so the gallery's own poll stays `304` and the tree
 * poll is the one that carries it: the listing's conditional read answers `200` with the Memory's row marked, and its lift
 * the same way. And what that poll costs on a Memory's page, measured: an idle one is the gate's reads and the revision's,
 * whatever the tree holds — no more than the gallery's own idle poll.
 */
@QuarkusTest
class OpenMemoryTreePollSliceTest {

    @Test
    fun aBlockOfAFolderAboveAnOpenMemoryAndItsLiftReachTheTreePollNotTheGallerys() {
        val mod = CaseTestKit.moderator()
        val (owner, scopeId) = seedMemberScope()
        val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, parentId = folder, wrappedContentKey = CONTENT_KEY)
        val treeTag = nodes(owner, scopeId).statusCode(200).body(markOf(memory), equalTo(false)).extract().header("ETag")
        val galleryTag = media(owner, scopeId, memory).statusCode(200).extract().header("ETag")
        nodes(owner, scopeId, treeTag).statusCode(304) // control: the conditional path answers 304 at all

        asMember(owner, scopeId, body(listOf(item("NODE", folder)))).then().statusCode(201)
        val caseId = openCase("NODE", folder)!!.id
        val measure = mod.take(caseId, "CONTENT", targetId = folder).then().statusCode(201).extract().path<String>("measures[0].id")

        // The gallery's poll does not carry a node block (no item revision moved) — the tree's does, the Memory marked.
        media(owner, scopeId, memory, galleryTag).statusCode(304)
        val blocked = nodes(owner, scopeId, treeTag).statusCode(200).body(markOf(memory), equalTo(true)).extract().header("ETag")
        assertNotEquals(treeTag, blocked)
        nodes(owner, scopeId, blocked).statusCode(304)

        // The lift moves the tree's tag again, and the next poll serves the Memory unmarked.
        mod.lift(caseId, measure).then().statusCode(200)
        nodes(owner, scopeId, blocked).statusCode(200).body(markOf(memory), equalTo(false))
    }

    @Test
    fun anIdleTreePollIsAFewStatementsWhateverTheTreeHoldsAndNoMoreThanTheGallerys() {
        val small = memoryIn(extraNodes = 0)
        val large = memoryIn(extraNodes = 200)
        nodes(small.owner, small.scopeId, small.treeTag).statusCode(304) // warm
        nodes(large.owner, large.scopeId, large.treeTag).statusCode(304)

        val idleSmall = StatementCount.during { nodes(small.owner, small.scopeId, small.treeTag).statusCode(304) }
        val idleLarge = StatementCount.during { nodes(large.owner, large.scopeId, large.treeTag).statusCode(304) }
        val fullLarge = StatementCount.during { nodes(large.owner, large.scopeId).statusCode(200) }
        val idleGallery = StatementCount.during { media(small.owner, small.scopeId, small.memory, small.galleryTag).statusCode(304) }
        assertEquals(idleSmall, idleLarge, "an idle tree poll of 201 nodes issues what one of a single node does")
        assertTrue(idleSmall < fullLarge, "an idle tree poll reads no row: $idleSmall statements against a listing's $fullLarge")
        assertTrue(idleSmall <= idleGallery, "the tree's idle poll ($idleSmall) costs no more than the gallery's ($idleGallery)")
    }

    private data class OpenMemory(val owner: String, val scopeId: String, val memory: String, val treeTag: String, val galleryTag: String)

    private fun memoryIn(extraNodes: Int): OpenMemory {
        val (owner, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        repeat(extraNodes) { seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY) }
        val treeTag = nodes(owner, scopeId).statusCode(200).extract().header("ETag")
        val galleryTag = media(owner, scopeId, memory).statusCode(200).extract().header("ETag")
        return OpenMemory(owner, scopeId, memory, treeTag, galleryTag)
    }

    private fun markOf(nodeId: String) = "find { it.id == '$nodeId' }.blockedByOperator"

    private fun nodes(userId: String, scopeId: String, ifNoneMatch: String? = null): ValidatableResponse =
        given().header("Authorization", bearer(userId)).apply { if (ifNoneMatch != null) header("If-None-Match", ifNoneMatch) }
            .`when`().get("/api/scopes/$scopeId/nodes").then()

    private fun media(userId: String, scopeId: String, nodeId: String, ifNoneMatch: String? = null): ValidatableResponse =
        given().header("Authorization", bearer(userId)).apply { if (ifNoneMatch != null) header("If-None-Match", ifNoneMatch) }
            .`when`().get("/api/scopes/$scopeId/nodes/$nodeId/media").then()
}
