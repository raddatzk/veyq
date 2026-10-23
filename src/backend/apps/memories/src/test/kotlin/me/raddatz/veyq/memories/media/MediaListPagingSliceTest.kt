package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.sharing.ShareWriteProof
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.path.json.JsonPath
import io.quarkus.test.junit.QuarkusTest
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The media list in pages (cr-gallery-list-paging, ADR 0033 decision 6): every answer carries at most `limit` rows and
 * says `more` while rows remain; the client asks again from the last row's cursor. The revisions it keeps are the FIRST
 * page's — read before that page's rows — so a change committed while it pages is pulled by its next poll. A guest pages
 * its node by the media id (no time of the Memory's activity reaches a guest, ADR 0081 decision 4).
 */
@QuarkusTest
class MediaListPagingSliceTest {

    /** What a page answered, as the client keeps it. */
    private data class Page(val ids: List<String>, val metas: Map<String, String?>, val cursor: String?, val more: Boolean, val revision: Int, val deleteRevision: Int, val mediaIds: List<String>?)

    @Test
    fun pagesArriveCompleteAndAChangeWhilePagingReachesTheNextPollFromTheFirstPagesRevisions() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val seeded = MediaSeed.photos(scopeId, memory, 1201, derivatives = false)

        val held = LinkedHashMap<String, String?>() // the client's items: id -> sealed meta
        val first = page(userId, scopeId, memory, limit = 500)
        assertEquals(500, first.ids.size, "a page holds the limit")
        assertTrue(first.more, "rows remain past the first page")
        held.putAll(first.metas)

        // Between the first and the third page another member edits a row the client already holds and trashes one.
        val edited = first.ids[3]
        val trashed = first.ids[7]
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"wrappedMeta":"ZWRpdGVk","nodeEpoch":0,"metaRevision":0}""")
            .`when`().put("/api/scopes/$scopeId/media/$edited/meta").then().statusCode(200)
        val second = page(userId, scopeId, memory, limit = 500, since = first.cursor, sinceDelete = first.deleteRevision)
        held.putAll(second.metas)
        given().header("Authorization", bearer(userId))
            .`when`().delete("/api/scopes/$scopeId/media/$trashed").then().statusCode(204)
        var last = page(userId, scopeId, memory, limit = 500, since = second.cursor, sinceDelete = first.deleteRevision)
        held.putAll(last.metas)
        while (last.more) {
            last = page(userId, scopeId, memory, limit = 500, since = last.cursor, sinceDelete = first.deleteRevision)
            held.putAll(last.metas)
        }
        assertTrue(held.keys.containsAll(seeded), "every seeded row arrived across the pages")

        // The next poll, from the revisions the client keeps — the first page's — and the last page's cursor.
        val kept = first
        val poll = given().header("Authorization", bearer(userId)).header("If-None-Match", kept.revision.toString())
            .queryParam("since", last.cursor).queryParam("sinceDelete", kept.deleteRevision)
            .`when`().get("/api/scopes/$scopeId/nodes/$memory/media")
        assertEquals(200, poll.statusCode, "a change committed while paging moved the revision past the kept one")
        val delta = toPage(poll.jsonPath())
        held.putAll(delta.metas)
        delta.mediaIds?.let { ids -> held.keys.retainAll(ids.toSet()) }

        assertEquals("ZWRpdGVk", held[edited], "the edit of a row already paged is delivered")
        assertTrue(trashed !in held, "the trash of a row already paged is reconciled")
        assertEquals((seeded - trashed).toSet(), held.keys, "the client holds exactly the live list")
    }

    @Test
    fun aReadWithoutALimitIsOnePageOfTheCap() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        MediaSeed.photos(scopeId, memory, 1001, derivatives = false)
        val all = page(userId, scopeId, memory, limit = null)
        assertEquals(1000, all.ids.size)
        assertTrue(all.more)
        val rest = page(userId, scopeId, memory, limit = null, since = all.cursor)
        assertEquals(1, rest.ids.size)
        assertTrue(!rest.more)
    }

    @Test
    fun aLimitOutsideOneToTheCapIs400() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        for (limit in listOf(0, 1001)) {
            given().header("Authorization", bearer(userId)).queryParam("limit", limit)
                .`when`().get("/api/scopes/$scopeId/nodes/$memory/media")
                .then().statusCode(400).body("code", equalTo("invalid_request"))
        }
        for (limit in listOf(1, 1000)) { // the bounds themselves are in
            given().header("Authorization", bearer(userId)).queryParam("limit", limit)
                .`when`().get("/api/scopes/$scopeId/nodes/$memory/media")
                .then().statusCode(200)
        }
    }

    @Test
    fun theTrashPagesOnTheSameCursor() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val trashed = MediaSeed.photos(scopeId, memory, 5, derivatives = false, trashed = true)
        MediaSeed.photos(scopeId, memory, 2, derivatives = false) // live: never in the trash's pages
        val seen = mutableListOf<String>()
        var p = page(userId, scopeId, memory, limit = 2, trashed = true)
        seen += p.ids
        while (p.more) {
            p = page(userId, scopeId, memory, limit = 2, since = p.cursor, trashed = true)
            seen += p.ids
        }
        assertEquals(trashed.toSet(), seen.toSet())
        assertEquals(5, seen.size, "no row twice")
    }

    @Test
    fun aGuestPagesTheNodeItHasOpenByIdAndANodeOutsideTheShareIsTheShares404() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val chapter = seedNode(scopeId, MemoriesNodeKinds.CHAPTER, parentId = memory, wrappedContentKey = CONTENT_KEY)
        val outside = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val inMemory = MediaSeed.photos(scopeId, memory, 3, derivatives = false)
        val inChapter = MediaSeed.photos(scopeId, chapter, 3, derivatives = false)
        MediaSeed.photos(scopeId, outside, 1, derivatives = false)
        val shareId = share(userId, memory)

        // The chapter alone, one medium a page, in id order, continued after the last id.
        val seen = mutableListOf<String>()
        var after: String? = null
        do {
            val page = guestPage(shareId, chapter, after, limit = 1).then().statusCode(200).extract().jsonPath()
            val ids = page.getList<String>("media.mediaId")
            assertEquals(1, ids.size)
            assertTrue(page.getList<String>("media.nodeId").all { it == chapter }, "only the asked node's media")
            seen += ids
            after = ids.last()
        } while (page.getBoolean("more"))
        assertEquals(inChapter.size, seen.size, "no medium twice")
        assertEquals(inChapter.toSet(), seen.toSet())

        // Without a node: the whole subtree.
        val whole = guestPage(shareId, null, null, limit = null).then().statusCode(200).body("more", equalTo(false))
            .extract().jsonPath().getList<String>("media.mediaId")
        assertEquals((inMemory + inChapter).toSet(), whole.toSet())

        // A node the link does not serve answers exactly what an unknown link answers.
        val outsideAnswer = guestPage(shareId, outside, null, limit = null).then().statusCode(404).extract().asString()
        val unknownAnswer = guestPage("A".repeat(43), chapter, null, limit = null).then().statusCode(404).extract().asString()
        assertEquals(unknownAnswer, outsideAnswer)
        guestPage(shareId, UUID.randomUUID().toString(), null, limit = null).then().statusCode(404).body("code", equalTo("share_not_found"))
    }

    private fun page(
        userId: String, scopeId: String, nodeId: String, limit: Int?, since: String? = null, sinceDelete: Int? = null,
        trashed: Boolean = false,
    ): Page {
        val r = given().header("Authorization", bearer(userId))
            .apply { if (limit != null) queryParam("limit", limit) }
            .apply { if (since != null) queryParam("since", since) }
            .apply { if (sinceDelete != null) queryParam("sinceDelete", sinceDelete) }
            .apply { if (trashed) queryParam("state", "trashed") }
            .`when`().get("/api/scopes/$scopeId/nodes/$nodeId/media")
            .then().statusCode(200).extract().jsonPath()
        return toPage(r)
    }

    private fun toPage(j: JsonPath): Page {
        val ids = j.getList<String>("media.mediaId")
        val metas = LinkedHashMap<String, String?>()
        ids.forEachIndexed { i, id -> metas[id] = j.getString("media[$i].wrappedMeta") }
        val cursor = j.getString("cursor")
        return Page(ids, metas, cursor, j.getBoolean("more"), j.getInt("mediaRevision"), j.getInt("deleteRevision"), j.getList("mediaIds"))
    }

    private fun guestPage(shareId: String, nodeId: String?, after: String?, limit: Int?) =
        given().apply { if (nodeId != null) queryParam("nodeId", nodeId) }
            .apply { if (after != null) queryParam("after", after) }
            .apply { if (limit != null) queryParam("limit", limit) }
            .`when`().get("/api/shares/$shareId/media")

    private fun share(userId: String, nodeId: String): String =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"$nodeId","role":"READ","wrap":"d3JhcA==","sealedName":"c2VhbGVk",${ShareWriteProof.recovery()}}""")
            .`when`().post("/api/shares")
            .then().statusCode(201).body("shareId", notNullValue())
            .extract().path("shareId")
}
