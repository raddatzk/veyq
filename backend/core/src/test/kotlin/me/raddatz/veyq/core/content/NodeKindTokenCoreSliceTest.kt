package me.raddatz.veyq.core.content

import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import java.util.Base64
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.hasItem
import org.hamcrest.CoreMatchers.not
import org.junit.jupiter.api.Test

/**
 * The node `kind` on the wire is an open, bounded token (`_common` `NodeKind`, `^[A-Z][A-Z0-9_]{0,15}$`; ADR 0079,
 * cr-platform-contract-split slice 4). Three gates, one per test, each with its control:
 * - the token's **shape** is the request annotation's — 400 before the handler (the generator emits no pattern of
 *   its own, so `CreateNodeRequest.kind` carries it);
 * - a well-formed kind **no rule claims** is refused by `NodeKindRules`' fail-closed seam (`illegal_structure`);
 * - a stored node of such a kind is **served as stored**: nothing decodes it into a closed set (before the token, the
 *   generated enum's `decode` threw on it).
 * Core alone (`FOLDER` is the only kind it claims), so `EXPENSE` stands for any kind of an absent application; the
 * share half (`illegal_scope`) needs Memories present and is `NodeKindTokenShareSliceTest` in `:apps:memories`.
 */
@QuarkusTest
class NodeKindTokenCoreSliceTest {

    @Test
    fun aKindOutsideTheTokenShapeIs400BeforeTheHandler() {
        val (userId, scopeId) = seedMemberScope()
        // lower case, 17 characters, a leading digit — each outside `^[A-Z][A-Z0-9_]{0,15}$`
        for (kind in listOf("memory", "ABCDEFGHIJKLMNOPQ", "1FOLDER")) {
            create(userId, scopeId, kind).then().statusCode(400)
        }
        // Control: 16 characters is inside the shape, so it reaches the handler — and no rule claims it.
        create(userId, scopeId, "ABCDEFGHIJKLMNOP").then().statusCode(422).body("code", equalTo("illegal_structure"))
    }

    @Test
    fun aWellFormedKindNoRuleClaimsIsIllegalStructure() {
        val (userId, scopeId) = seedMemberScope()
        create(userId, scopeId, "EXPENSE").then().statusCode(422).body("code", equalTo("illegal_structure"))
        given().header("Authorization", bearer(userId)).`when`().get("/api/scopes/$scopeId/nodes")
            .then().statusCode(200).body("kind", not(hasItem("EXPENSE")))
        // Control: core's own kind still creates.
        create(userId, scopeId, "FOLDER").then().statusCode(201)
    }

    @Test
    fun aStoredNodeOfAnUnclaimedKindIsServedAsStored() {
        val (userId, scopeId) = seedMemberScope()
        val expense = seedNode(scopeId, "EXPENSE", wrappedContentKey = null)
        val folder = seedNode(scopeId, "FOLDER", wrappedContentKey = null)
        given().header("Authorization", bearer(userId)).`when`().get("/api/scopes/$scopeId/nodes")
            .then().statusCode(200)
            .body("find { it.id == '$expense' }.kind", equalTo("EXPENSE"))
            .body("find { it.id == '$folder' }.kind", equalTo("FOLDER"))
        // The trash serves it the same way once it is deleted.
        given().header("Authorization", bearer(userId)).`when`().delete("/api/scopes/$scopeId/nodes/$expense")
            .then().statusCode(204)
        given().header("Authorization", bearer(userId)).`when`().get("/api/scopes/$scopeId/trash/nodes")
            .then().statusCode(200).body("find { it.id == '$expense' }.kind", equalTo("EXPENSE"))
    }

    private fun create(userId: String, scopeId: String, kind: String): Response =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"nodeId":"${UUID.randomUUID()}","kind":"$kind","encryptedName":"YQ==","slugToken":"${slug()}","orderIndex":0}""")
            .`when`().post("/api/scopes/$scopeId/nodes")

    private fun slug(): String = Base64.getEncoder().encodeToString(UUID.randomUUID().toString().toByteArray())
}
