package me.raddatz.veyq.core.content

import me.raddatz.veyq.core.blob.AttachOutcome
import me.raddatz.veyq.core.blob.AttachmentStore
import me.raddatz.veyq.core.blob.AttachmentUploads
import me.raddatz.veyq.core.blob.BlobUploadStore
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.sharing.GuestTreeShare
import me.raddatz.veyq.core.sharing.GuestTreeShare.Link
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * The scope's tree revision and the node listing's conditional GET (ADR 0081), core alone. The revision is the
 * listing's `ETag`; a poll echoing it as `If-None-Match` is a `304` while nothing a level view shows changed and a
 * `200` with a higher tag after any change that does. So the cases are the **write paths**: each one is made over the
 * path a client uses (HTTP where there is one, the store where an application's completion is the caller) and the
 * revision must have moved by exactly one — and, as the control, a refused or no-op write must move it by none.
 * A forgotten bump is a live view that never learns of a change, which nothing else here would notice.
 *
 * The Depot and drop-box paths (a file upload, a replace, a file delete, a guest's upload, an identity folder) need an
 * application and are `DepotTreeRevisionSliceTest`'s. The race is `TreeRevisionRaceCoreSliceTest`.
 */
@QuarkusTest
class TreeRevisionCoreSliceTest {

    @Inject lateinit var nodes: NodesStore
    @Inject lateinit var purge: NodeSubtreePurge
    @Inject lateinit var attachments: AttachmentStore
    @Inject lateinit var blobs: BlobUploadStore

    // --- the conditional GET ---

    @Test
    fun aFreshScopeListsAtRevisionZeroAndAnEchoOfItIs304() {
        val (userId, scopeId) = seedMemberScope()
        assertEquals("\"0\"", list(userId, scopeId).statusCode(200).extract().header("ETag"), "no row reads as 0")
        list(userId, scopeId, ifNoneMatch = "\"0\"").statusCode(304).header("ETag", equalTo(null))
    }

    @Test
    fun anUnchangedScopeAnswers304WithNoBodyAndAChangedOneA200WithTheNewTag() {
        val (userId, scopeId) = seedMemberScope()
        val first = createFolder(userId, scopeId)
        val tag = tagOf(userId, scopeId)
        assertEquals("\"1\"", tag)

        val notModified = list(userId, scopeId, ifNoneMatch = tag).statusCode(304).extract()
        assertEquals("", notModified.asString(), "a 304 carries no body")

        createFolder(userId, scopeId)
        list(userId, scopeId, ifNoneMatch = tag).statusCode(200).header("ETag", equalTo("\"2\""))
            .body("size()", equalTo(2)).body("find { it.id == '$first' }.id", equalTo(first))
    }

    @Test
    fun aWeakOrUnquotedEchoMatchesAndAnythingElseIsAPlain200() {
        val (userId, scopeId) = seedMemberScope()
        createFolder(userId, scopeId)
        list(userId, scopeId, ifNoneMatch = "W/\"1\"").statusCode(304)
        list(userId, scopeId, ifNoneMatch = "1").statusCode(304)
        for (junk in listOf("\"0\"", "\"2\"", "\"x\"", "\"-1\"", "*", "")) {
            list(userId, scopeId, ifNoneMatch = junk).statusCode(200)
        }
    }

    /** The access gate runs before the compare: a non-member must not learn a revision, nor that it matched. */
    @Test
    fun aNonMemberIs404WhateverItEchoes() {
        val (_, scopeId) = seedMemberScope()
        val outsider = seedUser()
        list(outsider, scopeId, ifNoneMatch = "\"0\"").statusCode(404).body("code", equalTo("scope_not_found"))
        list(outsider, scopeId).statusCode(404)
    }

    @Test
    fun theRevisionIsPerScope() {
        val (userId, scopeId) = seedMemberScope()
        val (otherUser, otherScope) = seedMemberScope()
        createFolder(userId, scopeId)
        createFolder(userId, scopeId)
        assertEquals("\"2\"", tagOf(userId, scopeId))
        assertEquals("\"0\"", tagOf(otherUser, otherScope), "another scope's changes are not this one's")
    }

    // --- every write a level view shows moves it by one ---

    @Test
    fun createMovesItOnce() {
        val (userId, scopeId) = seedMemberScope()
        assertEquals(1L, moved(scopeId) { createFolder(userId, scopeId) })
    }

    @Test
    fun aRenameMovesIt() {
        val t = tree()
        assertEquals(1L, moved(t.scopeId) { place(t, t.child, to = t.a, version = 0).statusCode(204) })
    }

    @Test
    fun aMoveMovesIt() {
        val t = tree()
        assertEquals(1L, moved(t.scopeId) { place(t, t.child, to = t.b, version = 0).statusCode(204) })
    }

    @Test
    fun aMetaWriteMovesIt() {
        val t = tree()
        assertEquals(1L, moved(t.scopeId) { patch(t, t.child, """{"wrappedMeta":"bWV0YQ==","metaRevision":0}""").statusCode(204) })
    }

    @Test
    fun aReorderMovesIt() {
        val t = tree()
        assertEquals(1L, moved(t.scopeId) { patch(t, t.child, """{"orderIndex":3}""").statusCode(204) })
    }

    @Test
    fun aTrashMovesIt() {
        val t = tree()
        assertEquals(1L, moved(t.scopeId) { delete(t, t.a).statusCode(204) })
    }

    @Test
    fun aRestoreMovesIt() {
        val t = tree()
        delete(t, t.a).statusCode(204)
        assertEquals(1L, moved(t.scopeId) { restore(t, t.a).statusCode(204) })
    }

    @Test
    fun thePurgeOfAnExpiredSubtreeMovesIt() {
        val t = tree()
        delete(t, t.a).statusCode(204)
        assertEquals(1L, moved(t.scopeId) { QuarkusTransaction.requiringNew().run { purge.eraseExpiredIn(t.scopeId, Instant.now().plusSeconds(60)) } })
    }

    @Test
    fun aFirstAttachAReplaceAndADetachEachMoveIt() {
        val (userId, scopeId) = seedMemberScope()
        val node = seedNode(scopeId, EXPENSE, wrappedContentKey = CONTENT_KEY)
        val first = AttachmentUploads.open(blobs, userId, scopeId, node, SLOT)
        assertEquals(1L, moved(scopeId) { attach(scopeId, node, first, expected = null) })

        val second = AttachmentUploads.open(blobs, userId, scopeId, node, SLOT)
        assertEquals(1L, moved(scopeId) { attach(scopeId, node, second, expected = first.blobId) })

        assertEquals(1L, moved(scopeId) { assertEquals(AttachmentStore.DetachOutcome.Detached, attachments.detach(scopeId, node, SLOT, second.blobId)) })
    }

    @Test
    fun aGuestsCreateAndPlacementMoveIt() {
        val t = guestTree()
        val created = UUID.randomUUID().toString()
        assertEquals(1L, moved(t.scopeId) { GuestTreeShare.create(t.link, t.root, NodeEntity.FOLDER, nodeId = created).statusCode(201) })
        assertEquals(1L, moved(t.scopeId) { GuestTreeShare.place(t.link, created, t.root, placementRevision = 0).statusCode(204) })
    }

    // --- what changes nothing moves nothing ---

    @Test
    fun aRefusedWriteMovesNothing() {
        val t = tree()
        val taken = GuestTreeShare.slug()
        place(t, t.child, to = t.a, version = 0, slugToken = taken).statusCode(204) // placement revision 1; `taken` lives under `a`
        patch(t, t.child, """{"wrappedMeta":"bWV0YQ==","metaRevision":0}""").statusCode(204) // meta revision 1
        val atRoot = GuestTreeShare.slug()
        createWithSlug(t.userId, t.scopeId, atRoot, parent = null).statusCode(201)
        assertEquals(0L, moved(t.scopeId) {
            place(t, t.child, to = t.a, version = 0).statusCode(409) // a stale placement
            place(t, t.b, to = t.a, version = 0, slugToken = taken).statusCode(409) // a slug taken under the destination
            patch(t, t.child, """{"wrappedMeta":"c3RhbGU=","metaRevision":0}""").statusCode(409) // a stale meta write
            patch(t, UUID.randomUUID().toString(), """{"orderIndex":1}""").statusCode(404) // no such node
            createWithSlug(t.userId, t.scopeId, atRoot, parent = null).statusCode(409) // a slug taken at the root
            restore(t, t.b).statusCode(404) // a live node is no trash root
        })
    }

    @Test
    fun aDeleteOfATrashedSubtreeAndANoOpUpdateMoveNothing() {
        val t = tree()
        delete(t, t.a).statusCode(204)
        assertEquals(0L, moved(t.scopeId) {
            delete(t, t.a).statusCode(204) // E3: already trashed — a no-op
            patch(t, t.b, "{}").statusCode(204) // nothing to write
        })
    }

    @Test
    fun aConflictingAttachMovesNothing() {
        val (userId, scopeId) = seedMemberScope()
        val node = seedNode(scopeId, EXPENSE, wrappedContentKey = CONTENT_KEY)
        val held = AttachmentUploads.open(blobs, userId, scopeId, node, SLOT)
        attach(scopeId, node, held, expected = null)
        val loser = AttachmentUploads.open(blobs, userId, scopeId, node, SLOT)
        assertEquals(0L, moved(scopeId) {
            assertEquals(AttachOutcome.Conflict(held.blobId), attach(scopeId, node, loser, expected = UUID.randomUUID().toString()))
            assertEquals(AttachmentStore.DetachOutcome.Replaced, attachments.detach(scopeId, node, SLOT, UUID.randomUUID().toString()))
        })
    }

    // --- the guest's conditional resolve (ADR 0081: a digest of what it serves, NOT the scope revision) ---

    @Test
    fun aGuestsResolveIs304UntilTheSharedSubtreeChanges() {
        val t = guestTree()
        val tag = resolve(t.link).statusCode(200).extract().header("ETag")
        assertNotNull(tag)
        assertEquals(tag, resolve(t.link).statusCode(200).extract().header("ETag"), "the same content, the same tag")
        resolve(t.link, ifNoneMatch = tag).statusCode(304).header("ETag", equalTo(tag))

        GuestTreeShare.create(t.link, t.root, NodeEntity.FOLDER).statusCode(201)
        val changed = resolve(t.link, ifNoneMatch = tag).statusCode(200).extract().header("ETag")
        assertNotNull(changed)
        assert(changed != tag) { "a change inside the subtree moves the tag" }
        resolve(t.link, ifNoneMatch = changed).statusCode(304)
    }

    /** The reason the guest's tag is no revision: a change outside the shared subtree must be invisible to the guest. */
    @Test
    fun aChangeOutsideTheSharedSubtreeDoesNotMoveAGuestsTag() {
        val t = guestTree()
        val tag = resolve(t.link).extract().header("ETag")
        val before = QuarkusTransaction.requiringNew().call { nodes.treeRevision(t.scopeId) }

        createFolder(t.userId, t.scopeId) // beside the shared subtree, at the scope root
        place(Tree(t.userId, t.scopeId, t.outside, t.root, t.outside), t.outside, to = null, version = 0).statusCode(204)

        assertEquals(before + 2, QuarkusTransaction.requiringNew().call { nodes.treeRevision(t.scopeId) }, "the scope revision DID move twice")
        resolve(t.link, ifNoneMatch = tag).statusCode(304)
    }

    @Test
    fun aRevokedOrUnknownLinkIs404ForAPollingGuest() {
        val t = guestTree()
        val tag = resolve(t.link).extract().header("ETag")
        QuarkusTransaction.requiringNew().run {
            me.raddatz.veyq.core.sharing.ShareEntity.update("enabled = false where id = ?1", t.link.shareId)
        }
        resolve(t.link, ifNoneMatch = tag).statusCode(404).body("code", equalTo("share_not_found"))
        resolve(Link(me.raddatz.veyq.core.TestIds.shareId(), ""), ifNoneMatch = tag).statusCode(404)
    }

    @Test
    fun aWrongTagIsAPlainResolve() {
        val t = guestTree()
        for (junk in listOf("\"0\"", "\"1\"", "x", "")) resolve(t.link, ifNoneMatch = junk).statusCode(200)
    }

    // --- helpers ---

    private data class Tree(val userId: String, val scopeId: String, val a: String, val b: String, val child: String)

    private fun tree(): Tree {
        val (userId, scopeId) = seedMemberScope()
        val a = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY, encryptedName = "YQ==")
        val b = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY, encryptedName = "Yg==")
        val child = seedNode(scopeId, NodeEntity.FOLDER, parentId = a, wrappedContentKey = CONTENT_KEY, encryptedName = "Y2hpbGQ=")
        return Tree(userId, scopeId, a, b, child)
    }

    private data class GuestTree(val userId: String, val scopeId: String, val root: String, val outside: String, val link: Link)

    /** A READ_WRITE link on `root`, with `outside` beside it at the scope root. */
    private fun guestTree(): GuestTree {
        val (userId, scopeId) = seedMemberScope()
        val root = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val outside = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        return GuestTree(userId, scopeId, root, outside, GuestTreeShare.seed(userId, scopeId, root))
    }

    /** How far [body] moved the scope's tree revision. */
    private fun moved(scopeId: String, body: () -> Unit): Long {
        val before = QuarkusTransaction.requiringNew().call { nodes.treeRevision(scopeId) }
        body()
        return QuarkusTransaction.requiringNew().call { nodes.treeRevision(scopeId) } - before
    }

    private fun list(userId: String, scopeId: String, ifNoneMatch: String? = null): ValidatableResponse {
        val request = given().header("Authorization", bearer(userId))
        if (ifNoneMatch != null) request.header("If-None-Match", ifNoneMatch)
        return request.`when`().get("/api/scopes/$scopeId/nodes").then()
    }

    private fun tagOf(userId: String, scopeId: String): String = list(userId, scopeId).statusCode(200).extract().header("ETag")

    private fun resolve(link: Link, ifNoneMatch: String? = null): ValidatableResponse {
        val request = given()
        if (ifNoneMatch != null) request.header("If-None-Match", ifNoneMatch)
        return request.`when`().get("/api/shares/${link.shareId}").then()
    }

    private fun createFolder(userId: String, scopeId: String): String = UUID.randomUUID().toString().also {
        createWithSlug(userId, scopeId, GuestTreeShare.slug(), parent = null, nodeId = it).statusCode(201)
    }

    private fun createWithSlug(userId: String, scopeId: String, slug: String, parent: String?, nodeId: String = UUID.randomUUID().toString()): ValidatableResponse {
        val parentJson = parent?.let { ""","parentId":"$it"""" } ?: ""
        return given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"nodeId":"$nodeId","kind":"FOLDER","encryptedName":"YQ==","slugToken":"$slug","orderIndex":0,"wrappedContentKey":"a21lbQ==","epoch":0$parentJson}""")
            .`when`().post("/api/scopes/$scopeId/nodes").then()
    }

    private fun place(t: Tree, node: String, to: String?, version: Long, slugToken: String = GuestTreeShare.slug()): ValidatableResponse {
        val parent = to?.let { "\"parentId\":\"$it\"," } ?: ""
        return patch(
            t, node,
            """{$parent"encryptedName":"cGxhY2Vk","slugToken":"$slugToken","wrappedContentKey":"cmUtd3JhcHBlZA==","epoch":0,"placementRevision":$version}""",
        )
    }

    private fun patch(t: Tree, node: String, body: String): ValidatableResponse =
        given().header("Authorization", bearer(t.userId)).contentType(ContentType.JSON).body(body)
            .`when`().patch("/api/scopes/${t.scopeId}/nodes/$node").then()

    private fun delete(t: Tree, node: String): ValidatableResponse =
        given().header("Authorization", bearer(t.userId)).`when`().delete("/api/scopes/${t.scopeId}/nodes/$node").then()

    private fun restore(t: Tree, node: String): ValidatableResponse =
        given().header("Authorization", bearer(t.userId)).contentType(ContentType.JSON).body("""{"slugToken":"${GuestTreeShare.slug()}"}""")
            .`when`().post("/api/scopes/${t.scopeId}/nodes/$node/restore").then()

    private fun attach(scopeId: String, node: String, upload: AttachmentUploads.Opened, expected: String?): AttachOutcome =
        attachments.attach(scopeId, node, setOf(EXPENSE), SLOT, upload.session, null, expected)

    private companion object {
        const val EXPENSE = "EXPENSE"
        const val SLOT = "RECEIPT"
    }
}
