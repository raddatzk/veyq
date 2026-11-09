package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.moderation.block.BlockKind
import me.raddatz.veyq.core.moderation.block.ModerationBlockEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.response.Response
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * A member's listing of every live medium of a space (`listScopeMedia`, ADR 0125 decision 8a) — what the search opens to
 * find a photo of any Memory by its capture time, place and name. Pages by media id across the scope's nodes; only live
 * media on live nodes of this scope; a non-member's 404; a `304` while nothing it depends on moved, a new tag after an
 * upload, a trash, a node's trash and a block; a blocked medium kept and marked; and a page read with the same
 * statements whatever it holds.
 */
@QuarkusTest
class ScopeMediaListSliceTest {

    @Test
    fun pagesByIdAcrossTheScopesNodesEveryMediumOnce() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val chapter = seedNode(scopeId, MemoriesNodeKinds.CHAPTER, parentId = memory, wrappedContentKey = CONTENT_KEY)
        val other = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val byNode = mapOf(memory to MediaSeed.photos(scopeId, memory, 3), chapter to MediaSeed.photos(scopeId, chapter, 2), other to MediaSeed.photos(scopeId, other, 2))

        val seen = mutableListOf<Pair<String, String>>() // mediaId to nodeId, as served
        var after: String? = null
        var pages = 0
        do {
            val page = read(userId, scopeId, limit = 3, after = after).then().statusCode(200).extract().jsonPath()
            val ids = page.getList<String>("media.mediaId")
            assertTrue(ids.size <= 3, "a page holds at most the limit")
            seen += ids.zip(page.getList<String>("media.nodeId"))
            after = ids.lastOrNull()
            pages++
        } while (page.getBoolean("more"))
        assertEquals(3, pages, "seven media in pages of three")
        assertEquals(7, seen.size, "no medium twice")
        assertEquals(byNode.flatMap { (node, ids) -> ids.map { it to node } }.toSet(), seen.toSet(), "every medium, tagged with its node")
    }

    @Test
    fun aTrashedNodesMediaATrashedMediumAndAnotherScopesMediaAreNeverListed() {
        val (userId, scopeId) = seedMemberScope()
        val kept = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val doomed = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val below = seedNode(scopeId, MemoriesNodeKinds.CHAPTER, parentId = doomed, wrappedContentKey = CONTENT_KEY)
        val live = MediaSeed.photos(scopeId, kept, 2)
        MediaSeed.photos(scopeId, kept, 1, trashed = true)
        val underDoomed = MediaSeed.photos(scopeId, doomed, 1) + MediaSeed.photos(scopeId, below, 1)
        // another scope's media, of another member: never this listing's, whatever its nodes
        val elsewhere = seedMemberScope()
        val foreign = seedNode(elsewhere.scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        MediaSeed.photos(elsewhere.scopeId, foreign, 2)

        assertEquals((live + underDoomed).toSet(), ids(userId, scopeId), "control: the node's media are listed while it is live")
        given().header("Authorization", bearer(userId)).`when`().delete("/api/scopes/$scopeId/nodes/$doomed").then().statusCode(204)
        assertEquals(live.toSet(), ids(userId, scopeId), "only the live media of live nodes of this scope")
    }

    @Test
    fun aNonMemberIsTheScopes404() {
        val (owner, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        MediaSeed.photos(scopeId, memory, 1)
        read(owner, scopeId).then().statusCode(200)
        val stranger = seedMemberScope().userId
        read(stranger, scopeId).then().statusCode(404).body("code", equalTo("scope_not_found"))
        read(stranger, UUID.randomUUID().toString()).then().statusCode(404).body("code", equalTo("scope_not_found"))
    }

    @Test
    fun anUnchangedScopeIsA304AndAnUploadATrashANodeTrashAndABlockEachMoveTheTag() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val chapter = seedNode(scopeId, MemoriesNodeKinds.CHAPTER, parentId = memory, wrappedContentKey = CONTENT_KEY)
        val first = MediaSeed.photos(scopeId, memory, 2)
        MediaSeed.photos(scopeId, chapter, 1)

        val tag = read(userId, scopeId).then().statusCode(200).extract().header("ETag")
        assertTrue(tag.matches(Regex("\"[0-9a-f]{32}\"")), "a quoted 128-bit digest: $tag")
        // Control: the conditional path answers 304 at all, so each 200 below is the tag moving, not a dead branch.
        read(userId, scopeId, ifNoneMatch = tag).then().statusCode(304).header("ETag", equalTo(tag))
        // A later page is never conditional and carries no tag.
        val later = read(userId, scopeId, after = first.min(), ifNoneMatch = tag).then().statusCode(200).extract().response()
        assertNull(later.header("ETag"))

        // An upload (the seed bumps the node's item revision as every writer does).
        MediaSeed.photos(scopeId, memory, 1)
        val tag2 = changed(userId, scopeId, tag)
        // A medium trashed.
        given().header("Authorization", bearer(userId)).`when`().delete("/api/scopes/$scopeId/media/${first[0]}").then().statusCode(204)
        val tag3 = changed(userId, scopeId, tag2)
        // A node trashed (its media's rows untouched: only the node's liveness moved).
        given().header("Authorization", bearer(userId)).`when`().delete("/api/scopes/$scopeId/nodes/$chapter").then().statusCode(204)
        val tag4 = changed(userId, scopeId, tag3)
        // A block bumps no revision; the listing marks a row, so the tag moves.
        block(BlockKind.CONTENT_ITEM, first[1], scopeId)
        changed(userId, scopeId, tag4)
    }

    @Test
    fun aBlockedMediumIsKeptAndMarkedAsInTheNodesListing() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val blockedNode = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val (item, free) = MediaSeed.photos(scopeId, memory, 2)
        val underBlock = MediaSeed.photos(scopeId, blockedNode, 1).single()
        block(BlockKind.CONTENT_ITEM, item, scopeId)
        block(BlockKind.CONTENT_NODE, blockedNode, scopeId)

        val page = read(userId, scopeId).then().statusCode(200).extract().jsonPath()
        val marks = page.getList<String>("media.mediaId").zip(page.getList<Boolean>("media.blockedByOperator")).toMap()
        assertEquals(mapOf(item to true, free to false, underBlock to true), marks)
        val nodeList = given().header("Authorization", bearer(userId)).`when`().get("/api/scopes/$scopeId/nodes/$memory/media")
            .then().statusCode(200).extract().jsonPath()
        val nodeMarks = nodeList.getList<String>("media.mediaId").zip(nodeList.getList<Boolean>("media.blockedByOperator")).toMap()
        assertEquals(nodeMarks, marks.filterKeys { it in nodeMarks }, "the same marks the node's listing serves")
    }

    @Test
    fun aPageOfOneMediumAndOfSixtyAcrossNodesIssueTheSameStatementsAndAnIdleReadReadsNoPage() {
        val small = seedMemberScope()
        MediaSeed.photos(small.scopeId, seedNode(small.scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY), 1)
        val big = seedMemberScope()
        repeat(3) { MediaSeed.photos(big.scopeId, seedNode(big.scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY), 20) }
        val list = { u: String, s: String, size: Int -> read(u, s).then().statusCode(200).body("media.size()", equalTo(size)).extract().header("ETag") }
        // warm: the session and the gate's first reads are not the list's
        list(small.userId, small.scopeId, 1)
        list(big.userId, big.scopeId, 60)
        val one = StatementCount.during { list(small.userId, small.scopeId, 1) }
        val sixty = StatementCount.during { list(big.userId, big.scopeId, 60) }
        assertEquals(one, sixty, "a page's statements do not grow with its media or its nodes")

        val tag = list(big.userId, big.scopeId, 60)
        val idle = StatementCount.during { read(big.userId, big.scopeId, ifNoneMatch = tag).then().statusCode(304) }
        assertTrue(idle < sixty, "an idle read reads no page: $idle statements against a page's $sixty")
    }

    private fun changed(userId: String, scopeId: String, before: String): String {
        val r = read(userId, scopeId, ifNoneMatch = before).then().statusCode(200).extract().response()
        val now = r.header("ETag")
        assertNotEquals(before, now)
        read(userId, scopeId, ifNoneMatch = now).then().statusCode(304)
        return now
    }

    private fun ids(userId: String, scopeId: String): Set<String> {
        val page = read(userId, scopeId).then().statusCode(200).extract().jsonPath()
        assertFalse(page.getBoolean("more"))
        return page.getList<String>("media.mediaId").toSet()
    }

    private fun block(kind: BlockKind, targetId: String, scopeId: String) = QuarkusTransaction.requiringNew().run {
        ModerationBlockEntity().apply {
            id = UUID.randomUUID().toString(); this.kind = kind.name; this.targetId = targetId; this.scopeId = scopeId
            takenBy = UUID.randomUUID().toString(); takenAt = Instant.now()
            activeKey = ModerationBlockEntity.activeKeyOf(kind, targetId)
        }.persist()
    }

    private fun read(userId: String, scopeId: String, limit: Int? = null, after: String? = null, ifNoneMatch: String? = null): Response =
        given().header("Authorization", bearer(userId))
            .apply { if (limit != null) queryParam("limit", limit) }
            .apply { if (after != null) queryParam("after", after) }
            .apply { if (ifNoneMatch != null) header("If-None-Match", ifNoneMatch) }
            .`when`().get("/api/scopes/$scopeId/media")
}
