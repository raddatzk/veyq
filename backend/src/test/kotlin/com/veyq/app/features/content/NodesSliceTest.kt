package com.veyq.app.features.content

import com.veyq.app.features.account.login.SessionService
import com.veyq.app.shared.membership.MembershipEntity
import com.veyq.app.shared.membership.TenantRoots
import com.veyq.app.shared.tenant.TenantEntity
import com.veyq.app.shared.scope.ScopeEntity
import com.veyq.app.shared.identity.VeyqUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.hasItems
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Test
import java.time.Instant
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
    lateinit var sessions: SessionService

    private fun token(userId: String) = "Bearer ${sessions.issue(userId).accessToken}"

    @Test
    fun createTreeThenList() {
        val (userId, scopeId) = seedMemberScope()
        val folder = createNode(userId, scopeId, """{"kind":"FOLDER","encryptedName":"Zm9sZGVy","slugToken":"Zm9sZGVyLXNsdWc=","orderIndex":0}""")
        val memory = createNode(userId, scopeId, """{"parentId":"$folder","kind":"MEMORY","encryptedName":"bWVtb3J5","slugToken":"bWVtLXNsdWc=","orderIndex":0,"wrappedContentKey":"a21lbS13cmFw"}""")
        // a chapter carries its OWN K_mem, wrapped under the parent Memory's K_mem (recursive K_mem, cr-0060 / ADR 0012)
        val chapter = createNode(userId, scopeId, """{"parentId":"$memory","kind":"CHAPTER","encryptedName":"Y2hhcHRlcg==","slugToken":"Y2hhcC1zbHVn","orderIndex":0,"wrappedContentKey":"a2NoYXAtd3JhcA=="}""")

        given().header("Authorization", token(userId))
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
        createNode(userId, scopeId, """{"kind":"FOLDER","encryptedName":"YQ==","slugToken":"ZHVwZQ==","orderIndex":0}""")
        given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"kind":"FOLDER","encryptedName":"Yg==","slugToken":"ZHVwZQ==","orderIndex":1}""")
            .`when`().post("/api/scopes/$scopeId/nodes")
            .then().statusCode(409).body("code", equalTo("slug_taken"))
    }

    @Test
    fun chapterAtRootIs422() {
        val (userId, scopeId) = seedMemberScope()
        given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"kind":"CHAPTER","encryptedName":"Yw==","slugToken":"Y2hhcA==","orderIndex":0}""")
            .`when`().post("/api/scopes/$scopeId/nodes")
            .then().statusCode(422).body("code", equalTo("illegal_structure"))
    }

    @Test
    fun nonMemberIs404() {
        val (_, scopeId) = seedMemberScope()
        val outsider = seedUser()
        given().header("Authorization", token(outsider)).contentType(ContentType.JSON)
            .body("""{"kind":"FOLDER","encryptedName":"Yg==","slugToken":"b3V0","orderIndex":0}""")
            .`when`().post("/api/scopes/$scopeId/nodes")
            .then().statusCode(404).body("code", equalTo("scope_not_found"))
    }

    @Test
    fun unauthenticatedIs401() {
        given().contentType(ContentType.JSON)
            .body("""{"kind":"FOLDER","encryptedName":"Yg==","slugToken":"cw==","orderIndex":0}""")
            .`when`().post("/api/scopes/${UUID.randomUUID()}/nodes")
            .then().statusCode(401).body("code", equalTo("authentication_failed"))
    }

    @Test
    fun renameThenDeleteSubtree() {
        val (userId, scopeId) = seedMemberScope()
        val folder = createNode(userId, scopeId, """{"kind":"FOLDER","encryptedName":"b2xk","slugToken":"b2xkLXM=","orderIndex":0}""")
        val memory = createNode(userId, scopeId, """{"parentId":"$folder","kind":"MEMORY","encryptedName":"bQ==","slugToken":"bS1z","orderIndex":0,"wrappedContentKey":"aw=="}""")

        // rename the folder (new ciphertext name + recomputed slug)
        given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"encryptedName":"bmV3","slugToken":"bmV3LXM="}""")
            .`when`().patch("/api/scopes/$scopeId/nodes/$folder")
            .then().statusCode(204)
        given().header("Authorization", token(userId)).`when`().get("/api/scopes/$scopeId/nodes")
            .then().statusCode(200).body("find { it.id == '$folder' }.encryptedName", equalTo("bmV3"))

        // delete the folder → its subtree (the memory) goes too
        given().header("Authorization", token(userId)).`when`().delete("/api/scopes/$scopeId/nodes/$folder")
            .then().statusCode(204)
        given().header("Authorization", token(userId)).`when`().get("/api/scopes/$scopeId/nodes")
            .then().statusCode(200).body("id", org.hamcrest.CoreMatchers.not(hasItems(folder, memory)))
    }

    @Test
    fun crossScopeDeleteIsRejected() {
        // victim scope B holds a Memory
        val (userB, scopeB) = seedMemberScope()
        val victimNode = createNode(userB, scopeB, """{"kind":"MEMORY","encryptedName":"dmljdGlt","slugToken":"dmljLXM=","orderIndex":0,"wrappedContentKey":"aw=="}""")
        // attacker is a member of their OWN scope A, not of B (every account is a member of its personal scope)
        val (userA, scopeA) = seedMemberScope()

        // addressing the foreign node through the attacker's own scope must NOT delete it: a NoScope-shaped 404,
        // indistinguishable from an absent scope (non-enumerable) — never a 204 that hard-deletes another scope's node.
        given().header("Authorization", token(userA))
            .`when`().delete("/api/scopes/$scopeA/nodes/$victimNode")
            .then().statusCode(404).body("code", equalTo("scope_not_found"))

        // the foreign node survives under B
        given().header("Authorization", token(userB)).`when`().get("/api/scopes/$scopeB/nodes")
            .then().statusCode(200).body("id", hasItems(victimNode))
    }

    @Test
    fun wrappedMetaRoundTripsAndPartialUpdateLeavesItUnchanged() {
        val (userId, scopeId) = seedMemberScope()
        // create a Memory carrying an opaque sealed wrappedMeta blob (cr-0067) — the server never inspects it
        val memory = createNode(
            userId, scopeId,
            """{"kind":"MEMORY","encryptedName":"bWVt","slugToken":"bWV0YS1z","orderIndex":0,"wrappedContentKey":"aw==","wrappedMeta":"bWV0YS12MQ=="}""",
        )
        given().header("Authorization", token(userId)).`when`().get("/api/scopes/$scopeId/nodes")
            .then().statusCode(200)
            .body("find { it.id == '$memory' }.wrappedMeta", equalTo("bWV0YS12MQ=="))

        // a rename that OMITS wrappedMeta must leave the sealed blob unchanged (partial semantics)
        given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"encryptedName":"bmV3","slugToken":"bmV3LXNsdWc="}""")
            .`when`().patch("/api/scopes/$scopeId/nodes/$memory")
            .then().statusCode(204)
        given().header("Authorization", token(userId)).`when`().get("/api/scopes/$scopeId/nodes")
            .then().statusCode(200)
            .body("find { it.id == '$memory' }.wrappedMeta", equalTo("bWV0YS12MQ=="))

        // a partial update that SETS wrappedMeta replaces it
        given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"wrappedMeta":"bWV0YS12Mg=="}""")
            .`when`().patch("/api/scopes/$scopeId/nodes/$memory")
            .then().statusCode(204)
        given().header("Authorization", token(userId)).`when`().get("/api/scopes/$scopeId/nodes")
            .then().statusCode(200)
            .body("find { it.id == '$memory' }.wrappedMeta", equalTo("bWV0YS12Mg=="))
    }

    // --- helpers ---

    private fun createNode(userId: String, scopeId: String, body: String): String =
        given().header("Authorization", token(userId)).contentType(ContentType.JSON).body(body)
            .`when`().post("/api/scopes/$scopeId/nodes")
            .then().statusCode(201).body("nodeId", notNullValue())
            .extract().path("nodeId")

    /** Seed a user + an ACTIVE tenant + a PERSONAL scope the user is a member of. Returns (userId, scopeId). */
    private fun seedMemberScope(): Pair<String, String> {
        val userId = seedUser()
        val tenantId = UUID.randomUUID().toString()
        val scopeId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            TenantEntity().apply {
                id = tenantId; name = "Fam"; domain = "t-$tenantId.example.com"
                ownerEmail = "o-$tenantId@example.com"; status = TenantEntity.ACTIVE; createdAt = Instant.now()
            }.persist()
            ScopeEntity().apply {
                id = scopeId; this.tenantId = tenantId; kind = ScopeEntity.PERSONAL; createdAt = Instant.now()
            }.persist()
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); this.userId = userId; this.tenantId = tenantId
                this.scopeId = scopeId; role = MembershipEntity.OWNER; wrappedGrant = "Z3JhbnQ="; createdAt = Instant.now()
            }.persist()
            TenantRoots.join(userId, tenantId)
        }
        return userId to scopeId
    }

    private fun seedUser(): String {
        val userId = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            VeyqUser().apply {
                id = userId; email = "u-$userId@example.com"; serverRole = "MEMBER"
                suiteId = "v1"; identityPublicKey = "aWQ="; wrappedIdentityPrivateKey = "aXA="
                authPublicKey = "YXA="; createdAt = Instant.now()
            }.persist()
        }
        return userId
    }
}
