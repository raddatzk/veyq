package me.raddatz.veyq.core.content

import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import java.util.Base64
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A create or a placement that names a parent node states the epoch it read it at (ADR 0109 point 6), and the contract
 * says so as a schema rule (`dependentSchemas` on `CreateNodeRequest` and `UpdateNodeRequest`, inbox #534). So a body
 * without it is the shape fault `400 invalid_request` **before** any answer about the instance's state: a parent nobody
 * has, a trashed parent, a scope the caller is no member of. Answered after them (as it was), a request the contract
 * refuses reads to the API fuzzer as one accepted and then refused for the state (ST201).
 *
 * The control the root keeps: `parentId == scopeId` is the scope root's slot, which this server reads as the root and
 * which needs no `parentEpoch` — unchanged until the owner decides inbox #534's open question.
 */
@QuarkusTest
class NodeParentEpochShapeCoreSliceTest {

    @Test
    fun aCreateUnderANodeWithoutItsParentEpochIs400BeforeAnyStateAnswer() {
        val t = tree()
        val (stranger, _) = seedMemberScope()
        val trashed = seedNode(t.scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        given().header("Authorization", bearer(t.userId)).`when`().delete("/api/scopes/${t.scopeId}/nodes/$trashed").then().statusCode(204)
        for ((caller, parent) in listOf(
            t.userId to t.folder, // a live parent: refused for the shape, as it always was
            t.userId to UUID.randomUUID().toString(), // a parent nobody has — was 422 illegal_structure
            t.userId to trashed, // a trashed parent — was 422 illegal_structure
            stranger to t.folder, // a caller who is no member — was the non-member's 404
        )) {
            create(caller, t.scopeId, parent, parentEpoch = null).statusCode(400).body("code", equalTo("invalid_request"))
        }
        assertEquals(0L, children(t.folder), "a refused create stores nothing")

        // controls: with its parentEpoch the create lands; the root by a null and by its own slot needs none
        create(t.userId, t.scopeId, t.folder, parentEpoch = 0).statusCode(201)
        create(t.userId, t.scopeId, null, parentEpoch = null).statusCode(201)
        create(t.userId, t.scopeId, t.scopeId, parentEpoch = null).statusCode(201)
        assertEquals(1L, children(t.folder))
    }

    @Test
    fun aPlacementUnderANodeWithoutItsParentEpochIs400BeforeAnyStateAnswer() {
        val t = tree()
        val (stranger, _) = seedMemberScope()
        for ((caller, to) in listOf(
            t.userId to t.folder,
            t.userId to UUID.randomUUID().toString(), // was 422 illegal_structure
            t.userId to t.node, // the node itself — a cycle, was 422 illegal_structure
            stranger to t.folder, // was the non-member's 404
        )) {
            place(caller, t, to, parentEpoch = null).statusCode(400).body("code", equalTo("invalid_request"))
        }
        assertEquals(t.scopeId to 0L, placement(t.node), "a refused placement writes nothing")

        // controls: with its parentEpoch the move lands; the root by its own slot needs none
        place(t.userId, t, t.folder, parentEpoch = 0, revision = 0).statusCode(204)
        assertEquals(t.folder to 1L, placement(t.node))
        place(t.userId, t, t.scopeId, parentEpoch = null, revision = 1).statusCode(204)
        assertEquals(t.scopeId to 2L, placement(t.node))
    }

    // --- helpers ---

    private data class Tree(val userId: String, val scopeId: String, val folder: String, val node: String)

    /** Two root folders: `folder`, the destination, and `node`, the one placed. */
    private fun tree(): Tree {
        val (userId, scopeId) = seedMemberScope()
        val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val node = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        return Tree(userId, scopeId, folder, node)
    }

    private fun create(caller: String, scopeId: String, parentId: String?, parentEpoch: Int?): ValidatableResponse {
        val parent = parentId?.let { "\"parentId\":\"$it\"," } ?: ""
        val epoch = parentEpoch?.let { "\"parentEpoch\":$it," } ?: ""
        return given().header("Authorization", bearer(caller)).contentType(ContentType.JSON)
            .body("""{$parent$epoch"nodeId":"${UUID.randomUUID()}","kind":"FOLDER","encryptedName":"bg==","slugToken":"${slug()}","orderIndex":0,"wrappedContentKey":"a21lbQ==","epoch":0}""")
            .`when`().post("/api/scopes/$scopeId/nodes").then()
    }

    private fun place(caller: String, t: Tree, to: String, parentEpoch: Int?, revision: Long = 0): ValidatableResponse {
        val epoch = parentEpoch?.let { "\"parentEpoch\":$it," } ?: ""
        return given().header("Authorization", bearer(caller)).contentType(ContentType.JSON)
            .body("""{"parentId":"$to",$epoch"encryptedName":"bW92ZWQ=","slugToken":"${slug()}","wrappedContentKey":"cmV3cmFw","epoch":0,"placementRevision":$revision}""")
            .`when`().patch("/api/scopes/${t.scopeId}/nodes/${t.node}").then()
    }

    private fun children(parent: String): Long = QuarkusTransaction.requiringNew().call { NodeEntity.count("parentId", parent) }

    private fun placement(node: String): Pair<String, Long> =
        QuarkusTransaction.requiringNew().call { NodeEntity.findById(node)!!.let { it.parentId to it.placementRevision } }

    private fun slug(): String = Base64.getEncoder().encodeToString(UUID.randomUUID().toString().toByteArray())
}
