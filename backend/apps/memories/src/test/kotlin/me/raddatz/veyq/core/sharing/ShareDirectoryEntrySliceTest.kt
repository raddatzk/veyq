package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.core.testing.TestFixtures.shareKeysJson
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.quarkus.test.junit.QuarkusTest
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * A share serves the directory entries of its subtree and nothing above it (ADR 0080 Decisions 2 and 9; bite tests
 * 9 and 12). Every descendant's `encryptedName` — sealed under its parent's content key, which the guest derives from
 * the root down — is served as stored; the **root's** own entry (its name, its wrap and its parent id, all under the
 * parent's key above the share) is not, nor any node outside the subtree. A WRITE_ONLY link holds no key of the
 * subtree, so it is served no node at all: no name, no content key.
 *
 * The server never opens a name: these are structural assertions about which opaque column values cross the wire.
 * Absences are asserted on the raw body, because a field the spec does not name would be dropped by a typed client
 * before any assertion saw it.
 */
@QuarkusTest
class ShareDirectoryEntrySliceTest {

    private val wrap = "d3JhcC1rLW1lbS11bmRlci1zaGFyZQ=="
    private val sealedName = "c2VhbGVkLW5hbWU="

    // Distinct opaque values per node, so the raw body can be searched for each one.
    private val folderName = "Zm9sZGVyLW5hbWU="
    private val folderWrap = "Zm9sZGVyLXdyYXA="
    private val rootName = "cm9vdC1uYW1l"
    private val rootWrap = "cm9vdC13cmFw"
    private val chapterName = "Y2hhcHRlci1uYW1l"
    private val nestedName = "bmVzdGVkLW5hbWU="
    private val siblingName = "c2libGluZy1uYW1l"

    private data class Tree(val userId: String, val scopeId: String, val folder: String, val memory: String, val chapter: String, val nested: String, val sibling: String)

    /** folder ⊃ { memory ⊃ chapter ⊃ nested, sibling chapter-less Memory } — the share is rooted at `memory`. */
    private fun seedTree(): Tree {
        val (userId, scopeId) = seedMemberScope(ScopeEntity.PERSONAL)
        val folder = seedNode(scopeId, NodeEntity.FOLDER, parentId = scopeId, wrappedContentKey = folderWrap, encryptedName = folderName)
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, parentId = folder, wrappedContentKey = rootWrap, encryptedName = rootName)
        val chapter = seedNode(scopeId, MemoriesNodeKinds.CHAPTER, parentId = memory, wrappedContentKey = CONTENT_KEY, encryptedName = chapterName)
        val nested = seedNode(scopeId, MemoriesNodeKinds.CHAPTER, parentId = chapter, wrappedContentKey = CONTENT_KEY, encryptedName = nestedName)
        val sibling = seedNode(scopeId, MemoriesNodeKinds.MEMORY, parentId = folder, wrappedContentKey = CONTENT_KEY, encryptedName = siblingName)
        return Tree(userId, scopeId, folder, memory, chapter, nested, sibling)
    }

    @Test
    fun aMemoryShareServesEveryDescendantsNameAndNothingOfTheRootsOwnEntry() {
        val t = seedTree()
        val shareId = createShare(t.userId, t.memory, "READ")

        val body = given().`when`().get("/api/shares/$shareId")
            .then().statusCode(200)
            // each descendant's name, exactly as stored (sealed under its parent's key — the server cannot tell)
            .body("nodes.find { it.id == '${t.chapter}' }.encryptedName", equalTo(chapterName))
            .body("nodes.find { it.id == '${t.chapter}' }.parentId", equalTo(t.memory))
            .body("nodes.find { it.id == '${t.nested}' }.encryptedName", equalTo(nestedName))
            .body("nodes.find { it.id == '${t.nested}' }.parentId", equalTo(t.chapter))
            // the root: present, but none of its own placement — no parent id, no wrap, no name key at all
            .body("nodes.find { it.id == '${t.memory}' }", notNullValue())
            .body("nodes.find { it.id == '${t.memory}' }.parentId", nullValue())
            .body("nodes.find { it.id == '${t.memory}' }.wrappedContentKey", nullValue())
            .body("nodes.size()", equalTo(3))
            .extract().asString()

        assertAll(
            { assertFalse(body.contains(rootName), "the root's encryptedName is under the parent's key: never served") },
            { assertFalse(body.contains(rootWrap), "the root's own wrap is under the parent's key: never served") },
            { assertFalse(body.contains(t.folder), "nothing above the root: no parent id") },
            { assertFalse(body.contains(folderName) || body.contains(folderWrap), "nothing above the root: no parent entry") },
            { assertFalse(body.contains(t.sibling) || body.contains(siblingName), "nothing outside the subtree") },
            { assertEquals(2, Regex("\"encryptedName\"").findAll(body).count(), "a name key per descendant, none for the root") },
        )
    }

    /** ADR 0080 Decision 3: a descendant's name and wrap are sealed at its placement version, so resolve serves the
     *  version beside them — the one a member's rename moved to — and none for the root, whose entry it does not serve. */
    @Test
    fun aResolveServesEachDescendantsPlacementVersionAndNoneForTheRoot() {
        val t = seedTree()
        given().header("Authorization", bearer(t.userId)).contentType(ContentType.JSON)
            .body("""{"parentId":"${t.memory}","encryptedName":"cmVuYW1lZA==","slugToken":"cmVuYW1lZC1zbHVn","wrappedContentKey":"cmUtd3JhcA==","epoch":0,"placementVersion":0}""")
            .`when`().patch("/api/scopes/${t.scopeId}/nodes/${t.chapter}").then().statusCode(204)
        val shareId = createShare(t.userId, t.memory, "READ")

        val body = given().`when`().get("/api/shares/$shareId")
            .then().statusCode(200)
            .body("nodes.find { it.id == '${t.chapter}' }.placementVersion", equalTo(1))
            .body("nodes.find { it.id == '${t.chapter}' }.encryptedName", equalTo("cmVuYW1lZA=="))
            .body("nodes.find { it.id == '${t.chapter}' }.wrappedContentKey", equalTo("cmUtd3JhcA=="))
            .body("nodes.find { it.id == '${t.nested}' }.placementVersion", equalTo(0))
            .body("nodes.find { it.id == '${t.memory}' }.placementVersion", nullValue())
            .extract().asString()
        assertEquals(2, Regex("\"placementVersion\"").findAll(body).count(), "a version per descendant, none for the root")
    }

    @Test
    fun aWriteOnlyResolveCarriesNoNameAndNoContentKey() {
        val t = seedTree()
        val shareId = createShare(t.userId, t.memory, "WRITE_ONLY")

        val body = given().`when`().get("/api/shares/$shareId")
            .then().statusCode(200)
            .body("role", equalTo("WRITE_ONLY"))
            .body("wrap", nullValue())
            .body("nodes.size()", equalTo(0))
            .extract().asString()

        assertAll(
            { assertFalse(body.contains("\"encryptedName\""), "a WRITE_ONLY guest holds no key to open a name with") },
            { assertFalse(body.contains("\"wrappedContentKey\""), "a WRITE_ONLY guest is handed no content key") },
            { assertFalse(listOf(rootName, chapterName, nestedName).any(body::contains), "no name of the subtree") },
            { assertFalse(listOf(rootWrap, CONTENT_KEY).any(body::contains), "no wrap of the subtree") },
        )
    }

    private fun createShare(userId: String, nodeId: String, role: String): String =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"$nodeId","role":"$role",${shareKeysJson(role, nodeId, wrap)},"sealedName":"$sealedName"}""")
            .`when`().post("/api/shares")
            .then().statusCode(201).body("shareId", notNullValue())
            .extract().path("shareId")
}
