package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.sharing.ShareWriteProof
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A page of the media list is read with the same number of SQL statements whatever it holds (cr-gallery-list-paging
 * decision 1): its originals, its derivatives and their blobs are read once per page, never once per photo. Measured
 * before the fix at 40,017 SELECTs for 10,000 photos (2 + one per derivative, per photo). Each of the three readers
 * the mapper serves is pinned — the member's live list, the trash, a guest's — with a page of one photo against a page
 * of sixty, every photo with its two derivative tiers. The count includes the request's own reads (the session, the
 * access gate, the revision), which do not grow with the page either; what the assertion holds is that nothing does.
 */
@QuarkusTest
class MediaListQueryCountSliceTest {

    @Test
    fun theMembersPageOfOnePhotoAndOfSixtyIssueTheSameStatements() {
        val (userId, scopeId) = seedMemberScope()
        val one = memoryWith(scopeId, 1)
        val sixty = memoryWith(scopeId, 60)
        val list = { node: String, size: Int ->
            given().header("Authorization", bearer(userId))
                .`when`().get("/api/scopes/$scopeId/nodes/$node/media")
                .then().statusCode(200).body("media.size()", equalTo(size))
        }
        list(one, 1) // warm: the session and the gate's first reads are not the list's
        assertEquals(StatementCount.during { list(one, 1) }, StatementCount.during { list(sixty, 60) }, "member list")
    }

    @Test
    fun theTrashPageOfOnePhotoAndOfSixtyIssueTheSameStatements() {
        val (userId, scopeId) = seedMemberScope()
        val one = memoryWith(scopeId, 1, trashed = true)
        val sixty = memoryWith(scopeId, 60, trashed = true)
        val list = { node: String, size: Int ->
            given().header("Authorization", bearer(userId)).queryParam("state", "trashed")
                .`when`().get("/api/scopes/$scopeId/nodes/$node/media")
                .then().statusCode(200).body("media.size()", equalTo(size))
        }
        list(one, 1)
        assertEquals(StatementCount.during { list(one, 1) }, StatementCount.during { list(sixty, 60) }, "trash list")
    }

    @Test
    fun aGuestsPageOfOnePhotoAndOfSixtyIssueTheSameStatements() {
        val (userId, scopeId) = seedMemberScope()
        val one = share(userId, memoryWith(scopeId, 1))
        val sixty = share(userId, memoryWith(scopeId, 60))
        val list = { shareId: String, size: Int -> ShareMediaRead.of(shareId).then().statusCode(200).body("media.size()", equalTo(size)) }
        list(one, 1)
        assertEquals(StatementCount.during { list(one, 1) }, StatementCount.during { list(sixty, 60) }, "share list")
    }

    private fun memoryWith(scopeId: String, n: Int, trashed: Boolean = false): String {
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        MediaSeed.photos(scopeId, memory, n, trashed = trashed)
        return memory
    }

    private fun share(userId: String, nodeId: String): String =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"$nodeId","role":"READ","wrap":"d3JhcA==","sealedName":"c2VhbGVk",${ShareWriteProof.recovery()}}""")
            .`when`().post("/api/shares")
            .then().statusCode(201).body("shareId", notNullValue())
            .extract().path("shareId")
}
