package me.raddatz.veyq.memories.media

import me.raddatz.veyq.core.items.NodeItemsRevisions
import me.raddatz.veyq.core.moderation.block.BlockKind
import me.raddatz.veyq.core.moderation.block.ModerationBlockEntity
import me.raddatz.veyq.core.sharing.ShareWriteProof
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.shareKeysJson
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.arc.Arc
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * A guest's gallery polls its listing as a member's does (inbox #497, share view = member view): `listShareMedia`'s first
 * page carries an `ETag` that is a digest of everything the read serves (ADR 0081 decision 4), and `If-None-Match` with it
 * answers `304` while that is unchanged. The tag moves when what the link shows moves, and on nothing else: not on a
 * change elsewhere in the space, and not on a move of the node's own item revision that changes no served row — the
 * signal a member's tag is, and the one that would tell an anonymous guest when the Memory is worked on.
 */
@QuarkusTest
class ShareMediaLivePollSliceTest {

    @Test
    fun aGuestsPollIsA304UntilWhatTheLinkServesChanges() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val outside = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val first = MediaSeed.photos(scopeId, memory, 2, derivatives = false)
        val shareId = share(userId, memory)

        val load = read(shareId, memory).then().statusCode(200).body("media.size()", equalTo(2)).extract().response()
        val tag = load.header("ETag")
        assertTrue(tag.matches(Regex("\"[0-9a-f]{32}\"")), "a quoted 128-bit digest, not a number: $tag")
        // Control: the conditional path answers 304 at all — so each 304 below is the tag holding, not a dead branch.
        read(shareId, memory, ifNoneMatch = tag).then().statusCode(304).header("ETag", equalTo(tag))

        // A change elsewhere in the space — another Memory's upload — is no change of what the link serves. (The cache
        // answers this poll, its key untouched; the next step's poll recomputes the digest, over a space that has changed
        // outside the link since, so that one holds the digest itself to it — measured: a digest that counted the space's
        // media went red there.)
        MediaSeed.photos(scopeId, outside, 1, derivatives = false)
        read(shareId, memory, ifNoneMatch = tag).then().statusCode(304)

        // The node's own item revision moves with no served row changed (what a medium moved between buckets, which a
        // guest is not shown, does): the member's tag moves — the lever moved the revision — and the guest's does not.
        val memberTag = memberTag(userId, scopeId, memory)
        QuarkusTransaction.requiringNew().run { Arc.container().instance(NodeItemsRevisions::class.java).get().bump(scopeId, memory) }
        assertNotEquals(memberTag, memberTag(userId, scopeId, memory), "the lever moves the node's item revision")
        read(shareId, memory, ifNoneMatch = tag).then().statusCode(304).header("ETag", equalTo(tag))

        // A medium added inside the shared node: 200, carrying it, under a new tag.
        val added = MediaSeed.photos(scopeId, memory, 1, derivatives = false).single()
        val changed = read(shareId, memory, ifNoneMatch = tag).then().statusCode(200).extract().response()
        assertEquals((first + added).toSet(), changed.jsonPath().getList<String>("media.mediaId").toSet())
        val tag2 = changed.header("ETag")
        assertNotEquals(tag, tag2)
        read(shareId, memory, ifNoneMatch = tag2).then().statusCode(304)

        // A medium trashed inside it: 200 without it.
        given().header("Authorization", bearer(userId)).`when`().delete("/api/scopes/$scopeId/media/${first[0]}").then().statusCode(204)
        val trashed = read(shareId, memory, ifNoneMatch = tag2).then().statusCode(200).extract().response()
        assertEquals(setOf(first[1], added), trashed.jsonPath().getList<String>("media.mediaId").toSet())
        val tag3 = trashed.header("ETag")
        assertNotEquals(tag2, tag3)

        // A block of the shared node marks every served row and bumps no item revision: the tag still moves.
        QuarkusTransaction.requiringNew().run {
            ModerationBlockEntity().apply {
                id = UUID.randomUUID().toString(); kind = BlockKind.CONTENT_NODE.name; targetId = memory; this.scopeId = scopeId
                takenBy = UUID.randomUUID().toString(); takenAt = Instant.now()
                activeKey = ModerationBlockEntity.activeKeyOf(BlockKind.CONTENT_NODE, memory)
            }.persist()
        }
        read(shareId, memory, ifNoneMatch = tag3).then().statusCode(200).body("media.blockedByOperator", equalTo(listOf(true, true)))
    }

    @Test
    fun theTagCoversEveryPageAndALaterPageIsNeitherTaggedNorConditional() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        MediaSeed.photos(scopeId, memory, 3, derivatives = false)
        val shareId = share(userId, memory)

        val whole = read(shareId, memory).then().statusCode(200).extract().response()
        val firstPage = read(shareId, memory, limit = 1).then().statusCode(200).body("more", equalTo(true)).extract().response()
        assertEquals(whole.header("ETag"), firstPage.header("ETag"), "a page of one is tagged with the whole listing")

        val last = firstPage.jsonPath().getList<String>("media.mediaId").single()
        val later = read(shareId, memory, limit = 1, after = last, ifNoneMatch = whole.header("ETag")).then().statusCode(200).extract().response()
        assertNull(later.header("ETag"), "a later page carries no tag")
        assertEquals(1, later.jsonPath().getList<String>("media.mediaId").size)
    }

    @Test
    fun anIdlePollReadsNoListingWhateverItsSize() {
        val (userId, scopeId) = seedMemberScope()
        val one = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val many = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        MediaSeed.photos(scopeId, one, 1, derivatives = false)
        MediaSeed.photos(scopeId, many, 1001, derivatives = false) // past one page of the cap
        val shareOne = share(userId, one)
        val shareMany = share(userId, many)
        val tagOne = read(shareOne, one).then().statusCode(200).extract().header("ETag")
        val tagMany = read(shareMany, many).then().statusCode(200).body("more", equalTo(true)).extract().header("ETag")
        read(shareOne, one, ifNoneMatch = tagOne).then().statusCode(304) // warm

        val idleOne = StatementCount.during { read(shareOne, one, ifNoneMatch = tagOne).then().statusCode(304) }
        val idleMany = StatementCount.during { read(shareMany, many, ifNoneMatch = tagMany).then().statusCode(304) }
        val pageOne = StatementCount.during { read(shareOne, one).then().statusCode(200) }
        assertEquals(idleOne, idleMany, "an idle poll of 1,001 media issues what one of a single medium does")
        assertTrue(idleOne < pageOne, "an idle poll reads no page: $idleOne statements against a page's $pageOne")
    }

    @Test
    fun aWriteOnlyLinkIsServedNothingUnderAConstantTag() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val shareId = share(userId, memory, role = "WRITE_ONLY")
        val tag = read(shareId, null).then().statusCode(200).body("media.size()", equalTo(0)).extract().header("ETag")
        MediaSeed.photos(scopeId, memory, 1, derivatives = false)
        read(shareId, null, ifNoneMatch = tag).then().statusCode(304)
        assertFalse(tag.isNullOrBlank())
    }

    private fun read(shareId: String, nodeId: String?, limit: Int? = null, after: String? = null, ifNoneMatch: String? = null): Response =
        given().apply { if (nodeId != null) queryParam("nodeId", nodeId) }
            .apply { if (limit != null) queryParam("limit", limit) }
            .apply { if (after != null) queryParam("after", after) }
            .apply { if (ifNoneMatch != null) header("If-None-Match", ifNoneMatch) }
            .`when`().get("/api/shares/$shareId/media")

    private fun memberTag(userId: String, scopeId: String, nodeId: String): String =
        given().header("Authorization", bearer(userId)).`when`().get("/api/scopes/$scopeId/nodes/$nodeId/media")
            .then().statusCode(200).extract().header("ETag")

    private fun share(userId: String, nodeId: String, role: String = "READ"): String =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"$nodeId","role":"$role",${shareKeysJson(role, nodeId)},"sealedName":"c2VhbGVk",${ShareWriteProof.recovery()}}""")
            .`when`().post("/api/shares")
            .then().statusCode(201).body("shareId", notNullValue())
            .extract().path("shareId")
}
