package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.moderation.report.ReportTestKit.asGuest
import me.raddatz.veyq.core.moderation.report.ReportTestKit.asMember
import me.raddatz.veyq.core.moderation.report.ReportTestKit.body
import me.raddatz.veyq.core.moderation.report.ReportTestKit.epochOne
import me.raddatz.veyq.core.moderation.report.ReportTestKit.item
import me.raddatz.veyq.core.moderation.report.ReportTestKit.openCase
import me.raddatz.veyq.core.node.CoreNodeKinds
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.FIXTURE_EDITOR
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.shareKeysJson
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.UUID

/**
 * Core's bookmark of a received link (ADR 0120, uc.sharing.keep-a-received-link) with core alone: where a member places
 * one, that it roots no link, that no link of a folder above it serves it or lets a guest act on it, and that this
 * instance's moderators are handed its name, never its key. Each refusal stands beside the control that passes the
 * same path with a folder, so a path that refused everything would be red.
 */
@QuarkusTest
class ShareBookmarkCoreSliceTest {

    private val bookmark = CoreNodeKinds.SHARE_BOOKMARK

    /** Decision 2: at the top level and in a folder, nothing inside a bookmark. */
    @Test
    fun aMemberPlacesABookmarkAtTheTopOrInAFolderAndNothingInsideIt() {
        val (userId, scopeId) = seedMemberScope(ScopeEntity.GROUP)
        val top = createNode(userId, scopeId, bookmark).statusCode(201).extract().path<String>("nodeId")
        val folder = createNode(userId, scopeId, NodeEntity.FOLDER).statusCode(201).extract().path<String>("nodeId")
        createNode(userId, scopeId, bookmark, parentId = folder).statusCode(201)

        for (kind in listOf(NodeEntity.FOLDER, bookmark)) {
            createNode(userId, scopeId, kind, parentId = top).statusCode(422).body("code", equalTo("illegal_structure"))
        }
        // the meta is stored as the client sealed it: the server reads none of it
        val stored = QuarkusTransaction.requiringNew().call { NodeEntity.findById(top)!! }
        assertEquals(bookmark, stored.kind)
        assertEquals(META, stored.wrappedMeta)
    }

    /** Decision 2: a bookmark is the root of no link, whatever the role — a folder beside it is (control). */
    @Test
    fun aBookmarkRootsNoLink() {
        val (userId, scopeId) = seedMemberScope(ScopeEntity.PERSONAL)
        val node = seedNode(scopeId, bookmark, wrappedContentKey = CONTENT_KEY)
        for (role in listOf("READ", "READ_WRITE", "WRITE_ONLY")) {
            postShare(userId, node, role).statusCode(422).body("code", equalTo("illegal_scope"))
        }
        postShare(userId, seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY), "READ").statusCode(201)
    }

    /**
     * Decision 7: a link of a folder that holds a bookmark serves its sibling folder and not the bookmark — no entry, no
     * wrap, no name, no meta — and its guest reports neither the bookmark nor its name (not in reach).
     */
    @Test
    fun aLinkAboveABookmarkDoesNotServeIt() {
        epochOne()
        val (userId, scopeId) = seedMemberScope(ScopeEntity.PERSONAL)
        val root = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val sibling = seedNode(scopeId, NodeEntity.FOLDER, parentId = root, wrappedContentKey = "c2libGluZy13cmFw", encryptedName = "c2libGluZy1uYW1l")
        val kept = seedBookmark(scopeId, parentId = root)
        val shareId: String = postShare(userId, root, "READ").statusCode(201).extract().path("shareId")

        val served = given().`when`().get("/api/shares/$shareId").then().statusCode(200)
            .body("nodes.find { it.id == '$sibling' }.wrappedContentKey", equalTo("c2libGluZy13cmFw")) // control
            .body("nodes.size()", equalTo(2))
            .extract().asString()
        for (absent in listOf(kept, BOOKMARK_WRAP, BOOKMARK_NAME, META, bookmark)) {
            assertFalse(served.contains(absent), "the link serves nothing of the bookmark: $absent")
        }

        for (kind in listOf("NODE", "NAME")) {
            asGuest(shareId, body(listOf(item(kind, kept)))).then().statusCode(404).body("code", equalTo("report_item_not_found"))
        }
        asGuest(shareId, body(listOf(item("NODE", sibling)))).then().statusCode(201) // control
    }

    /** Decision 7: a `READ_WRITE` guest neither moves nor renames a bookmark it was never served; a folder it does. */
    @Test
    fun aContributingGuestDoesNotPlaceABookmark() {
        val (userId, scopeId) = seedMemberScope(ScopeEntity.PERSONAL)
        val root = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val inner = seedNode(scopeId, NodeEntity.FOLDER, parentId = root, wrappedContentKey = CONTENT_KEY)
        val kept = seedBookmark(scopeId, parentId = root)
        val folder = seedNode(scopeId, NodeEntity.FOLDER, parentId = root, wrappedContentKey = CONTENT_KEY)
        val link = GuestTreeShare.seed(userId, scopeId, root)

        GuestTreeShare.place(link, kept, inner).statusCode(404).body("code", equalTo("node_not_found"))
        GuestTreeShare.place(link, kept, root).statusCode(404).body("code", equalTo("node_not_found")) // a rename in place
        GuestTreeShare.place(link, folder, inner).statusCode(204) // control: the same move of a folder
        assertEquals(root, QuarkusTransaction.requiringNew().call { NodeEntity.findById(kept)!!.parentId })
        // and no guest creates one (ADR 0120 decision 2; the contract's `creatableInFolder`)
        GuestTreeShare.create(link, root, bookmark).statusCode(422).body("code", equalTo("illegal_scope"))
    }

    /**
     * Decision 6: a member reports a bookmark here by its name only; a `NODE` item of one — its key, which opens another
     * link — is refused before anything is stored, and the same report of a folder is taken (control).
     */
    @Test
    fun aMemberReportsABookmarksNameButNeverItsKey() {
        epochOne()
        val m = seedMemberScope(ScopeEntity.GROUP)
        val kept = seedBookmark(m.scopeId, parentId = m.scopeId)
        val folder = seedNode(m.scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)

        asMember(m.userId, m.scopeId, body(listOf(item("NODE", kept)))).then().statusCode(422).body("code", equalTo("report_target_kind"))
        // the whole report is refused, the folder beside the bookmark included
        asMember(m.userId, m.scopeId, body(listOf(item("NODE", folder), item("NODE", kept)))).then().statusCode(422)
        assertNull(openCase("NODE", kept))
        assertNull(openCase("NODE", folder))

        asMember(m.userId, m.scopeId, body(listOf(item("NAME", kept)))).then().statusCode(201).body("reportId", notNullValue())
        assertNotNull(openCase("NAME", kept))
        asMember(m.userId, m.scopeId, body(listOf(item("NODE", folder)))).then().statusCode(201) // control
    }

    /** Decision 7 for the moderator: a report of a folder holds its subtree without the bookmark in it. */
    @Test
    fun aReportedFoldersHoldLeavesTheBookmarkOut() {
        epochOne()
        val m = seedMemberScope(ScopeEntity.GROUP)
        val folder = seedNode(m.scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val inner = seedNode(m.scopeId, NodeEntity.FOLDER, parentId = folder, wrappedContentKey = CONTENT_KEY)
        val kept = seedBookmark(m.scopeId, parentId = folder)
        val sharing = io.quarkus.arc.Arc.container().instance(SharingStore::class.java).get()

        val reach = QuarkusTransaction.requiringNew().call { sharing.liveSubtreeIds(m.scopeId, folder) }
        assertTrue(inner in reach, "control: the folder's own subtree is held")
        assertFalse(kept in reach, "the bookmark is not")
    }

    private fun seedBookmark(scopeId: String, parentId: String): String {
        val id = seedNode(scopeId, bookmark, parentId = parentId, wrappedContentKey = BOOKMARK_WRAP, encryptedName = BOOKMARK_NAME)
        QuarkusTransaction.requiringNew().run { NodeEntity.findById(id)!!.apply { wrappedMeta = META; metaWrittenBy(FIXTURE_EDITOR) } }
        return id
    }

    private fun createNode(userId: String, scopeId: String, kind: String, parentId: String? = null): ValidatableResponse {
        val slug = Base64.getEncoder().encodeToString(UUID.randomUUID().toString().toByteArray())
        val fields = mutableListOf(
            """"nodeId":"${UUID.randomUUID()}"""", """"kind":"$kind"""", """"encryptedName":"bg=="""", """"slugToken":"$slug"""",
            """"orderIndex":0""", """"wrappedContentKey":"a21lbQ=="""",
        )
        if (parentId != null) fields += listOf(""""parentId":"$parentId"""", """"parentEpoch":0""")
        if (kind == bookmark) fields += """"wrappedMeta":"$META""""
        return given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body(fields.joinToString(",", "{", "}"))
            .`when`().post("/api/scopes/$scopeId/nodes").then()
    }

    private fun postShare(userId: String, nodeId: String, role: String) =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"$nodeId","role":"$role",${shareKeysJson(role, nodeId)},"sealedName":"c2VhbGVk",${ShareWriteProof.recovery()}}""")
            .`when`().post("/api/shares").then()

    private companion object {
        const val META = "Ym9va21hcmstbWV0YQ=="
        const val BOOKMARK_WRAP = "Ym9va21hcmstd3JhcA=="
        const val BOOKMARK_NAME = "Ym9va21hcmstbmFtZQ=="
    }
}
