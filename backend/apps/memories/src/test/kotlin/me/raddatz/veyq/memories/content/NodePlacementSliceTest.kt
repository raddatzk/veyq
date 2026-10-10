package me.raddatz.veyq.memories.content

import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
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
 * The placement rule of a node's kind holds at a move's destination (ADR 0080 Decision 3, ADR 0079): a placement
 * write the kind's application refuses there is `422 illegal_structure` and writes nothing — the move, the re-sealed
 * name and the re-wrapped key alike. Memories' kinds, so it lives where Memories runs; core's checks of the same
 * write are `NodePlacementCoreSliceTest`.
 */
@QuarkusTest
class NodePlacementSliceTest {

    @Test
    fun aMoveTheDestinationsKindRuleRefusesIs422AndWritesNothing() {
        val (userId, scopeId) = seedMemberScope()
        val folder = seedNode(scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY)
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY, encryptedName = "bWVt")
        val chapter = seedNode(scopeId, MemoriesNodeKinds.CHAPTER, parentId = memory, wrappedContentKey = CONTENT_KEY, encryptedName = "Y2g=")

        // a chapter lives only in a Memory: neither at the root nor in a folder
        place(userId, scopeId, chapter, to = null).statusCode(422).body("code", equalTo("illegal_structure"))
        place(userId, scopeId, chapter, to = folder).statusCode(422).body("code", equalTo("illegal_structure"))
        // a Memory never lives in a Memory
        place(userId, scopeId, memory, to = memory).statusCode(422)
        assertEquals(listOf(memory, "Y2g=", CONTENT_KEY, 0L), row(chapter))

        // control: a Memory moves into a folder, its key re-wrapped under the folder's
        place(userId, scopeId, memory, to = folder, wrap = "dW5kZXItZm9sZGVy").statusCode(204)
        assertEquals(listOf(folder, "bW92ZWQ=", "dW5kZXItZm9sZGVy", 1L), row(memory))
    }

    private fun place(userId: String, scopeId: String, node: String, to: String?, wrap: String = "cmUtd3JhcA=="): ValidatableResponse {
        val parent = to?.let { "\"parentId\":\"$it\"," } ?: ""
        val slug = Base64.getEncoder().encodeToString(UUID.randomUUID().toString().toByteArray())
        return given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{$parent"encryptedName":"bW92ZWQ=","slugToken":"$slug","wrappedContentKey":"$wrap","epoch":0,"placementVersion":0}""")
            .`when`().patch("/api/scopes/$scopeId/nodes/$node").then()
    }

    private fun row(node: String): List<Any> = QuarkusTransaction.requiringNew().call {
        NodeEntity.findById(node)!!.let { listOf(it.parentId, it.encryptedName, it.wrappedContentKey, it.placementVersion) }
    }
}
