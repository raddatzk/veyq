package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Test

/**
 * The share half of the open node-kind token (ADR 0079, cr-platform-contract-split slice 4; the tree half is
 * `NodeKindTokenCoreSliceTest` in `:core`). With Memories present — so a refusal is about the kind, not about core
 * alone refusing every share — a node of a well-formed kind no application claims (`EXPENSE`, stored directly: the
 * create door refuses it) is refused by the `ShareGuestWrites` seam (`illegal_scope`), while a `MEMORY` shares; and a
 * shared subtree holding such a node serves it as stored on the anonymous resolve instead of failing to decode it.
 */
@QuarkusTest
class NodeKindTokenShareSliceTest {

    private val wrap = "d3JhcC1rLW1lbS11bmRlci1zaGFyZQ=="

    private val sealedName = "c2VhbGVkLW5hbWU="

    @Test
    fun aNodeOfAnUnclaimedKindIsIllegalScope() {
        val (userId, scopeId) = seedMemberScope(ScopeEntity.PERSONAL)
        val expense = seedNode(scopeId, "EXPENSE", parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        createShare(userId, expense).then().statusCode(422).body("code", equalTo("illegal_scope"))
        // Control: Memories claims MEMORY, so the same request on a Memory creates the share.
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        createShare(userId, memory).then().statusCode(201).body("shareId", notNullValue())
    }

    @Test
    fun aSharedSubtreeServesAnUnclaimedKindAsStored() {
        val (userId, scopeId) = seedMemberScope(ScopeEntity.PERSONAL)
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, parentId = scopeId, wrappedContentKey = CONTENT_KEY)
        val expense = seedNode(scopeId, "EXPENSE", parentId = memory, wrappedContentKey = CONTENT_KEY)
        val shareId: String = createShare(userId, memory).then().statusCode(201).extract().path("shareId")
        given().`when`().get("/api/shares/$shareId")
            .then().statusCode(200)
            .body("nodes.find { it.id == '$memory' }.kind", equalTo("MEMORY"))
            .body("nodes.find { it.id == '$expense' }.kind", equalTo("EXPENSE"))
    }

    private fun createShare(userId: String, nodeId: String): Response =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{${ShareWriteProof.newShare()},"nodeId":"$nodeId","role":"READ","wrap":"$wrap","sealedName":"$sealedName",${ShareWriteProof.recovery()}}""")
            .`when`().post("/api/shares")
}
