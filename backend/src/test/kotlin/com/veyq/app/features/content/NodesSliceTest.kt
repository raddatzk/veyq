package com.veyq.app.features.content

import com.veyq.app.features.media.MediaStore
import com.veyq.app.testing.RACE_ROUNDS
import com.veyq.app.testing.TestFixtures.CONTENT_KEY
import com.veyq.app.testing.TestFixtures.bearer
import com.veyq.app.testing.TestFixtures.seedMemberScope
import com.veyq.app.testing.TestFixtures.seedNode
import com.veyq.app.testing.TestFixtures.seedUser
import com.veyq.app.testing.whileUncommitted
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.hasItems
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Slice tests for the Memory tree (en.memory.tree, cr-0027): create folder/Memory/chapter + list; slug
 * uniqueness per parent (409); structural rules (422 — a chapter only lives in a Memory); scope-access
 * (non-member 404); rename + delete-subtree; operator-blind (the server stores only the ciphertext the
 * client sent). UUID-unique seeds so the JVM-lived H2 mem DB can't bleed across tests/classes.
 */
@QuarkusTest
class NodesSliceTest {

    @Inject
    lateinit var nodes: NodesStore

    @Inject
    lateinit var media: MediaStore

    @Test
    fun aRenameOverlappingAMediaAddKeepsTheMediaRevision() {
        // The rename reads the Memory before a media add's revision bump commits and flushes after it. A full-row
        // UPDATE wrote the older `mediaRevision` back: the live-updates signal stepped backwards, and a client whose
        // last seen revision was the restored one missed the add (uc.memory.live-updates, inbox #224).
        repeat(RACE_ROUNDS) { round ->
            val (_, scopeId) = seedMemberScope()
            val memory = seedNode(scopeId, NodeEntity.MEMORY, wrappedContentKey = CONTENT_KEY)
            val renamed = "cmVuYW1lZA==" // "renamed"
            whileUncommitted(
                change = { media.bumpMediaRevision(memory) },
                overlapping = { nodes.applyUpdate(memory, renamed, null, null, null, null) },
            )
            QuarkusTransaction.requiringNew().run {
                val node = NodeEntity.findById(memory)!!
                assertEquals(1, node.mediaRevision, "round $round: the rename rolled the media revision back")
                // Control: the rename itself landed.
                assertEquals(renamed, node.encryptedName, "round $round")
            }
        }
    }

    @Test
    fun createTreeThenList() {
        val (userId, scopeId) = seedMemberScope()
        val folder = createNode(userId, scopeId, """{"nodeId":"${UUID.randomUUID()}","kind":"FOLDER","encryptedName":"Zm9sZGVy","slugToken":"Zm9sZGVyLXNsdWc=","orderIndex":0}""")
        val memory = createNode(userId, scopeId, """{"parentId":"$folder","nodeId":"${UUID.randomUUID()}","kind":"MEMORY","encryptedName":"bWVtb3J5","slugToken":"bWVtLXNsdWc=","orderIndex":0,"wrappedContentKey":"a21lbS13cmFw"}""")
        // a chapter carries its OWN K_mem, wrapped under the parent Memory's K_mem (recursive K_mem, cr-0060 / ADR 0012)
        val chapter = createNode(userId, scopeId, """{"parentId":"$memory","nodeId":"${UUID.randomUUID()}","kind":"CHAPTER","encryptedName":"Y2hhcHRlcg==","slugToken":"Y2hhcC1zbHVn","orderIndex":0,"wrappedContentKey":"a2NoYXAtd3JhcA=="}""")

        given().header("Authorization", bearer(userId))
            .`when`().get("/api/scopes/$scopeId/nodes")
            .then().statusCode(200)
            .body("kind", hasItems("FOLDER", "MEMORY", "CHAPTER"))
            // operator-blind: both the Memory's and the chapter's wrapped K_mem come back verbatim; folder at root.
            .body("find { it.id == '$memory' }.wrappedContentKey", equalTo("a21lbS13cmFw"))
            .body("find { it.id == '$chapter' }.wrappedContentKey", equalTo("a2NoYXAtd3JhcA=="))
            .body("find { it.id == '$folder' }.parentId", equalTo(null))
    }

    @Test
    fun slugCollisionPerParentIs409() {
        val (userId, scopeId) = seedMemberScope()
        createNode(userId, scopeId, """{"nodeId":"${UUID.randomUUID()}","kind":"FOLDER","encryptedName":"YQ==","slugToken":"ZHVwZQ==","orderIndex":0}""")
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"nodeId":"${UUID.randomUUID()}","kind":"FOLDER","encryptedName":"Yg==","slugToken":"ZHVwZQ==","orderIndex":1}""")
            .`when`().post("/api/scopes/$scopeId/nodes")
            .then().statusCode(409).body("code", equalTo("slug_taken"))
    }

    @Test
    fun chapterAtRootIs422() {
        val (userId, scopeId) = seedMemberScope()
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"nodeId":"${UUID.randomUUID()}","kind":"CHAPTER","encryptedName":"Yw==","slugToken":"Y2hhcA==","orderIndex":0}""")
            .`when`().post("/api/scopes/$scopeId/nodes")
            .then().statusCode(422).body("code", equalTo("illegal_structure"))
    }

    @Test
    fun nonMemberIs404() {
        val (_, scopeId) = seedMemberScope()
        val outsider = seedUser()
        given().header("Authorization", bearer(outsider)).contentType(ContentType.JSON)
            .body("""{"nodeId":"${UUID.randomUUID()}","kind":"FOLDER","encryptedName":"Yg==","slugToken":"b3V0","orderIndex":0}""")
            .`when`().post("/api/scopes/$scopeId/nodes")
            .then().statusCode(404).body("code", equalTo("scope_not_found"))
    }

    @Test
    fun unauthenticatedIs401() {
        given().contentType(ContentType.JSON)
            .body("""{"nodeId":"${UUID.randomUUID()}","kind":"FOLDER","encryptedName":"Yg==","slugToken":"cw==","orderIndex":0}""")
            .`when`().post("/api/scopes/${UUID.randomUUID()}/nodes")
            .then().statusCode(401).body("code", equalTo("authentication_failed"))
    }

    @Test
    fun renameThenDeleteSubtree() {
        val (userId, scopeId) = seedMemberScope()
        val folder = createNode(userId, scopeId, """{"nodeId":"${UUID.randomUUID()}","kind":"FOLDER","encryptedName":"b2xk","slugToken":"b2xkLXM=","orderIndex":0}""")
        val memory = createNode(userId, scopeId, """{"parentId":"$folder","nodeId":"${UUID.randomUUID()}","kind":"MEMORY","encryptedName":"bQ==","slugToken":"bS1z","orderIndex":0,"wrappedContentKey":"aw=="}""")

        // rename the folder (new ciphertext name + recomputed slug)
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"encryptedName":"bmV3","slugToken":"bmV3LXM="}""")
            .`when`().patch("/api/scopes/$scopeId/nodes/$folder")
            .then().statusCode(204)
        given().header("Authorization", bearer(userId)).`when`().get("/api/scopes/$scopeId/nodes")
            .then().statusCode(200).body("find { it.id == '$folder' }.encryptedName", equalTo("bmV3"))

        // delete the folder → its subtree (the memory) goes too
        given().header("Authorization", bearer(userId)).`when`().delete("/api/scopes/$scopeId/nodes/$folder")
            .then().statusCode(204)
        given().header("Authorization", bearer(userId)).`when`().get("/api/scopes/$scopeId/nodes")
            .then().statusCode(200).body("id", org.hamcrest.CoreMatchers.not(hasItems(folder, memory)))
    }

    @Test
    fun crossScopeDeleteIsRejected() {
        // victim scope B holds a Memory
        val (userB, scopeB) = seedMemberScope()
        val victimNode = createNode(userB, scopeB, """{"nodeId":"${UUID.randomUUID()}","kind":"MEMORY","encryptedName":"dmljdGlt","slugToken":"dmljLXM=","orderIndex":0,"wrappedContentKey":"aw=="}""")
        // attacker is a member of their OWN scope A, not of B (every account is a member of its personal scope)
        val (userA, scopeA) = seedMemberScope()

        // addressing the foreign node through the attacker's own scope must NOT delete it: a NoScope-shaped 404,
        // indistinguishable from an absent scope (non-enumerable) — never a 204 that hard-deletes another scope's node.
        given().header("Authorization", bearer(userA))
            .`when`().delete("/api/scopes/$scopeA/nodes/$victimNode")
            .then().statusCode(404).body("code", equalTo("scope_not_found"))

        // the foreign node survives under B
        given().header("Authorization", bearer(userB)).`when`().get("/api/scopes/$scopeB/nodes")
            .then().statusCode(200).body("id", hasItems(victimNode))
    }

    @Test
    fun wrappedMetaRoundTripsAndPartialUpdateLeavesItUnchanged() {
        val (userId, scopeId) = seedMemberScope()
        // create a Memory carrying an opaque sealed wrappedMeta blob (cr-0067) — the server never inspects it
        val memory = createNode(
            userId, scopeId,
            """{"nodeId":"${UUID.randomUUID()}","kind":"MEMORY","encryptedName":"bWVt","slugToken":"bWV0YS1z","orderIndex":0,"wrappedContentKey":"aw==","wrappedMeta":"bWV0YS12MQ=="}""",
        )
        given().header("Authorization", bearer(userId)).`when`().get("/api/scopes/$scopeId/nodes")
            .then().statusCode(200)
            .body("find { it.id == '$memory' }.wrappedMeta", equalTo("bWV0YS12MQ=="))

        // a rename that OMITS wrappedMeta must leave the sealed blob unchanged (partial semantics)
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"encryptedName":"bmV3","slugToken":"bmV3LXNsdWc="}""")
            .`when`().patch("/api/scopes/$scopeId/nodes/$memory")
            .then().statusCode(204)
        given().header("Authorization", bearer(userId)).`when`().get("/api/scopes/$scopeId/nodes")
            .then().statusCode(200)
            .body("find { it.id == '$memory' }.wrappedMeta", equalTo("bWV0YS12MQ=="))

        // a partial update that SETS wrappedMeta replaces it
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"wrappedMeta":"bWV0YS12Mg=="}""")
            .`when`().patch("/api/scopes/$scopeId/nodes/$memory")
            .then().statusCode(204)
        given().header("Authorization", bearer(userId)).`when`().get("/api/scopes/$scopeId/nodes")
            .then().statusCode(200)
            .body("find { it.id == '$memory' }.wrappedMeta", equalTo("bWV0YS12Mg=="))
    }

    // --- helpers ---

    private fun createNode(userId: String, scopeId: String, body: String): String =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON).body(body)
            .`when`().post("/api/scopes/$scopeId/nodes")
            .then().statusCode(201).body("nodeId", notNullValue())
            .extract().path("nodeId")
}
