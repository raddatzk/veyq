package me.raddatz.veyq.core.content

import me.raddatz.veyq.core.testing.TestFixtures.FIXTURE_EDITOR
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.arc.Arc
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.persistence.EntityManager
import java.util.UUID
import me.raddatz.veyq.core.node.NodeEntity
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Slice tests for the Memory tree (en.memory.tree, cr-0027): create folder/Memory/chapter + list; slug
 * uniqueness per parent (409); structural rules (422 — a chapter only lives in a Memory); scope-access
 * (non-member 404); rename + delete-subtree; operator-blind (the server stores only the ciphertext the
 * client sent). UUID-unique seeds so the JVM-lived H2 mem DB can't bleed across tests/classes.
 *
 * **Split from `NodesSliceTest`** (`src/backend/CLAUDE.md`: a test lives where what it exercises runs): these methods run with core alone, so they live in
 * `:core`; the rest of the class is `NodesSliceTest` in `:apps:memories`. The bodies moved verbatim.
 */
@QuarkusTest
class NodesCoreSliceTest {

    @Test
    fun slugCollisionPerParentIs409() {
        val (userId, scopeId) = seedMemberScope()
        createNode(userId, scopeId, """{"nodeId":"${UUID.randomUUID()}","kind":"FOLDER","encryptedName":"YQ==","slugToken":"ZHVwZQ==","orderIndex":0,"wrappedContentKey":"a21lbQ=="}""")
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
            .body("""{"nodeId":"${UUID.randomUUID()}","kind":"FOLDER","encryptedName":"Yg==","slugToken":"ZHVwZQ==","orderIndex":1,"wrappedContentKey":"a21lbQ=="}""")
            .`when`().post("/api/scopes/$scopeId/nodes")
            .then().statusCode(409).body("code", equalTo("slug_taken"))
    }

    @Test
    fun nonMemberIs404() {
        val (_, scopeId) = seedMemberScope()
        val outsider = seedUser()
        given().header("Authorization", bearer(outsider)).contentType(ContentType.JSON)
            .body("""{"nodeId":"${UUID.randomUUID()}","kind":"FOLDER","encryptedName":"Yg==","slugToken":"b3V0","orderIndex":0,"wrappedContentKey":"a21lbQ=="}""")
            .`when`().post("/api/scopes/$scopeId/nodes")
            .then().statusCode(404).body("code", equalTo("scope_not_found"))
    }

    @Test
    fun unauthenticatedIs401() {
        given().contentType(ContentType.JSON)
            .body("""{"nodeId":"${UUID.randomUUID()}","kind":"FOLDER","encryptedName":"Yg==","slugToken":"cw==","orderIndex":0,"wrappedContentKey":"a21lbQ=="}""")
            .`when`().post("/api/scopes/${UUID.randomUUID()}/nodes")
            .then().statusCode(401).body("code", equalTo("authentication_failed"))
    }

    /**
     * ADR 0080 Decision 1: every node carries a content key, `FOLDER` included, so a create without the wrap (absent,
     * null or blank) is a 400 before the handler and stores nothing. The control, the same body with a wrap, is a 201,
     * so the 400s are the wrap's and not anything else in the body's.
     */
    @Test
    fun aFolderWithoutAWrapIs400AndNothingIsStored() {
        val (userId, scopeId) = seedMemberScope()
        val bare = "\"kind\":\"FOLDER\",\"encryptedName\":\"Yg==\",\"slugToken\":\"bm8td3JhcA==\",\"orderIndex\":0"
        for (wrap in listOf("", ",\"wrappedContentKey\":null", ",\"wrappedContentKey\":\"\"")) {
            given().header("Authorization", bearer(userId)).contentType(ContentType.JSON)
                .body("{\"nodeId\":\"${UUID.randomUUID()}\",$bare$wrap}")
                .`when`().post("/api/scopes/$scopeId/nodes")
                .then().statusCode(400)
        }
        assertEquals(0L, QuarkusTransaction.requiringNew().call { NodeEntity.count("scopeId", scopeId) }, "a refused create stored a node")
        createNode(userId, scopeId, "{\"nodeId\":\"${UUID.randomUUID()}\",$bare,\"wrappedContentKey\":\"a21lbQ==\"}")
    }

    /** The baseline holds the rule below the API (ADR 0080 Decision 1): a node row without a content key never lands,
     *  whichever path writes it. The control row, identical but for the key, does. */
    @Test
    fun theNodeTableRefusesARowWithoutAContentKey() {
        val (_, scopeId) = seedMemberScope()
        fun insert(withKey: Boolean) = QuarkusTransaction.requiringNew().run {
            val id = UUID.randomUUID().toString()
            val keyColumn = if (withKey) ", wrapped_content_key" else ""
            val keyValue = if (withKey) ", 'a21lbQ=='" else ""
            Arc.container().instance(EntityManager::class.java).get().createNativeQuery(
                "INSERT INTO veyq_node (id, scope_id, parent_id, kind, encrypted_name, slug_token, order_index, created_at, " +
                    "name_editor_user_id$keyColumn) " +
                    "VALUES ('$id', '$scopeId', '$scopeId', 'FOLDER', 'bg==', '$id', 0, CURRENT_TIMESTAMP, '${FIXTURE_EDITOR.userId}'$keyValue)",
            ).executeUpdate()
        }
        assertThrows(Exception::class.java) { insert(withKey = false) }
        insert(withKey = true)
        assertEquals(1L, QuarkusTransaction.requiringNew().call { NodeEntity.count("scopeId", scopeId) })
    }

    // --- helpers ---

    private fun createNode(userId: String, scopeId: String, body: String): String =
        given().header("Authorization", bearer(userId)).contentType(ContentType.JSON).body(body)
            .`when`().post("/api/scopes/$scopeId/nodes")
            .then().statusCode(201).body("nodeId", notNullValue())
            .extract().path("nodeId")
}
