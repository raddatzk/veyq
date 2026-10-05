package me.raddatz.veyq.core.content

import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import java.util.UUID
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Test

/**
 * Slice tests for the Memory tree (en.memory.tree, cr-0027): create folder/Memory/chapter + list; slug
 * uniqueness per parent (409); structural rules (422 — a chapter only lives in a Memory); scope-access
 * (non-member 404); rename + delete-subtree; operator-blind (the server stores only the ciphertext the
 * client sent). UUID-unique seeds so the JVM-lived H2 mem DB can't bleed across tests/classes.
 *
 * **Split from `NodesSliceTest`** (cr-platform-gradle-modules step 8a): these methods run with core alone, so they live in
 * `:core`; the rest of the class is `NodesSliceTest` in `:apps:memories`. The bodies moved verbatim.
 */
@QuarkusTest
class NodesCoreSliceTest {

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

    // --- helpers ---

    private fun createNode(userId: String, scopeId: String, body: String): String =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON).body(body)
            .`when`().post("/api/scopes/$scopeId/nodes")
            .then().statusCode(201).body("nodeId", notNullValue())
            .extract().path("nodeId")
}
