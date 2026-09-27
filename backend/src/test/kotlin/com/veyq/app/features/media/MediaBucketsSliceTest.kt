package com.veyq.app.features.media

import com.veyq.app.shared.TestIds
import com.veyq.app.features.account.login.SessionService
import com.veyq.app.shared.membership.MembershipEntity
import com.veyq.app.shared.membership.TenantRoots
import com.veyq.app.shared.tenant.TenantEntity
import com.veyq.app.features.content.NodeEntity
import com.veyq.app.shared.scope.ScopeEntity
import com.veyq.app.shared.identity.VeyqUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Slice tests for media uploader buckets (uc.memory.buckets, en.memory.buckets, ADR 0034, cr-0113). Covers: an
 * upload stamps the member's owner_key ("user:{id}") + materializes a lazy primary; the "Beiträge" list groups by
 * uploader with counts; a sub-bucket is created one level under the primary; a move re-links media; a sub-bucket
 * delete REPARENTS its media to the primary (media survive, never deleted); a member may curate another root's
 * SUB-buckets (rename/delete/create-under) + move WITHIN one root, but never a primary and never across owners
 * (cr-0123). In-memory store (metadata only).
 */
@QuarkusTest
class MediaBucketsSliceTest {

    @Inject
    lateinit var sessions: SessionService

    private fun token(userId: String) = "Bearer ${sessions.issue(userId).accessToken}"

    @Test
    fun uploadStampsOwnerKeyAndMaterializesPrimary() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY)
        completeOne(userId, scopeId, memory, wrap = "YQ==")

        // the media carries the member's owner_key + a concrete bucket (its primary)
        given().header("Authorization", token(userId))
            .`when`().get("/api/scopes/$scopeId/nodes/$memory/media")
            .then().statusCode(200)
            .body("media[0].ownerKey", equalTo("user:$userId"))
            .body("media[0].bucketId", notNullValue())

        // the Beiträge list shows exactly one primary bucket (the uploader's) with a count of 1
        given().header("Authorization", token(userId))
            .`when`().get("/api/scopes/$scopeId/nodes/$memory/buckets")
            .then().statusCode(200)
            .body("buckets.size()", equalTo(1))
            .body("buckets[0].isPrimary", equalTo(true))
            .body("buckets[0].ownerKey", equalTo("user:$userId"))
            .body("buckets[0].mediaCount", equalTo(1))
            .body("buckets[0].parentBucketId", nullValue())
    }

    @Test
    fun createSubBucketMoveThenDeleteReparents() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY)
        val a = completeOne(userId, scopeId, memory, wrap = "YQ==")
        val primary = firstPrimaryId(userId, scopeId, memory)

        // create a sub-bucket under the caller's primary (one level, owner inherited)
        val sub = given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"bucketId":"${TestIds.uuid()}","sealedName":"YnVja2V0"}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$memory/buckets")
            .then().statusCode(200)
            .body("isPrimary", equalTo(false))
            .body("parentBucketId", equalTo(primary))
            .body("ownerKey", equalTo("user:$userId"))
            .extract().path<String>("bucketId")

        // move the only medium into the sub-bucket → primary count 0, sub count 1
        given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"mediaIds":["$a"],"targetBucketId":"$sub"}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$memory/media-moves")
            .then().statusCode(204)
        given().header("Authorization", token(userId))
            .`when`().get("/api/scopes/$scopeId/nodes/$memory/buckets")
            .then().statusCode(200)
            .body("buckets.find { it.bucketId == '$primary' }.mediaCount", equalTo(0))
            .body("buckets.find { it.bucketId == '$sub' }.mediaCount", equalTo(1))

        // delete the sub-bucket → its media REPARENT to the primary (never deleted); the sub-bucket is gone
        given().header("Authorization", token(userId))
            .`when`().delete("/api/scopes/$scopeId/nodes/$memory/buckets/$sub")
            .then().statusCode(204)
        given().header("Authorization", token(userId))
            .`when`().get("/api/scopes/$scopeId/nodes/$memory/buckets")
            .then().statusCode(200)
            .body("buckets.size()", equalTo(1))
            .body("buckets[0].bucketId", equalTo(primary))
            .body("buckets[0].mediaCount", equalTo(1))
        // the medium itself still lives in the gallery
        given().header("Authorization", token(userId))
            .`when`().get("/api/scopes/$scopeId/nodes/$memory/media")
            .then().statusCode(200)
            .body("media.size()", equalTo(1))
            .body("media[0].mediaId", equalTo(a))
    }

    @Test
    fun renameSubBucketUpdatesSealedName() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY)
        completeOne(userId, scopeId, memory, wrap = "YQ==")
        val sub = createSub(userId, scopeId, memory, "YnVja2V0")

        given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"sealedName":"cmVuYW1lZA=="}""")
            .`when`().patch("/api/scopes/$scopeId/nodes/$memory/buckets/$sub")
            .then().statusCode(200)
            .body("bucketId", equalTo(sub))
            .body("sealedName", equalTo("cmVuYW1lZA=="))
    }

    @Test
    fun memberMayManageAnotherRootsSubBucket() {
        val (alice, scopeId, tenantId) = seedMemberScopeWithTenant()
        val bob = addMemberToScope(scopeId, tenantId)
        val memory = seedNode(scopeId, NodeEntity.MEMORY)
        completeOne(alice, scopeId, memory, wrap = "YQ==") // alice's primary
        val aliceSub = createSub(alice, scopeId, memory, "YWxpY2U=")

        // cr-0123: a fellow member may rename + delete another root's SUB-bucket (curating contributions) → 200 / 204
        given().header("Authorization", token(bob)).contentType(ContentType.JSON)
            .body("""{"sealedName":"Ym9i"}""")
            .`when`().patch("/api/scopes/$scopeId/nodes/$memory/buckets/$aliceSub")
            .then().statusCode(200).body("sealedName", equalTo("Ym9i"))
        given().header("Authorization", token(bob))
            .`when`().delete("/api/scopes/$scopeId/nodes/$memory/buckets/$aliceSub")
            .then().statusCode(204)
    }

    @Test
    fun primaryBucketNotRenameableNorDeletable() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY)
        completeOne(userId, scopeId, memory, wrap = "YQ==")
        val primary = firstPrimaryId(userId, scopeId, memory)

        given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"sealedName":"eA=="}""")
            .`when`().patch("/api/scopes/$scopeId/nodes/$memory/buckets/$primary")
            .then().statusCode(403).body("code", equalTo("illegal_target"))
        given().header("Authorization", token(userId))
            .`when`().delete("/api/scopes/$scopeId/nodes/$memory/buckets/$primary")
            .then().statusCode(403).body("code", equalTo("illegal_target"))
    }

    @Test
    fun memberCreatesSubUnderAnotherRootInheritingItsOwner() {
        val (alice, scopeId, tenantId) = seedMemberScopeWithTenant()
        val bob = addMemberToScope(scopeId, tenantId)
        val memory = seedNode(scopeId, NodeEntity.MEMORY)
        completeOne(alice, scopeId, memory, wrap = "YQ==")
        val alicePrimary = firstPrimaryId(alice, scopeId, memory)

        // cr-0123: bob nests a sub under alice's primary root (via parentBucketId) → it inherits alice's owner_key
        given().header("Authorization", token(bob)).contentType(ContentType.JSON)
            .body("""{"bucketId":"${TestIds.uuid()}","sealedName":"c3Vi","parentBucketId":"$alicePrimary"}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$memory/buckets")
            .then().statusCode(200)
            .body("ownerKey", equalTo("user:$alice"))
            .body("parentBucketId", equalTo(alicePrimary))
            .body("isPrimary", equalTo(false))
    }

    @Test
    fun createSubRejectsNonPrimaryParent() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY)
        completeOne(userId, scopeId, memory, wrap = "YQ==")
        val sub = createSub(userId, scopeId, memory, "c3Vi")

        // a sub-bucket can't be a parent — sub-buckets nest one level under a primary → 403
        given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"bucketId":"${TestIds.uuid()}","sealedName":"eA==","parentBucketId":"$sub"}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$memory/buckets")
            .then().statusCode(403).body("code", equalTo("illegal_target"))
    }

    @Test
    fun crossOwnerMoveIsRejected() {
        val (alice, scopeId, tenantId) = seedMemberScopeWithTenant()
        val bob = addMemberToScope(scopeId, tenantId)
        val memory = seedNode(scopeId, NodeEntity.MEMORY)
        val aliceMedia = completeOne(alice, scopeId, memory, wrap = "YQ==")
        val bobMedia = completeOne(bob, scopeId, memory, wrap = "Yg==")

        // one move may not span two owners (alice + bob) — a cross-owner move → 403 illegal_target
        given().header("Authorization", token(alice)).contentType(ContentType.JSON)
            .body("""{"mediaIds":["$aliceMedia","$bobMedia"],"targetBucketId":null}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$memory/media-moves")
            .then().statusCode(403).body("code", equalTo("illegal_target"))
    }

    @Test
    fun listBucketsByNonMemberIs404() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, NodeEntity.MEMORY)
        completeOne(userId, scopeId, memory, wrap = "YQ==")
        val outsider = seedUser()
        given().header("Authorization", token(outsider))
            .`when`().get("/api/scopes/$scopeId/nodes/$memory/buckets")
            .then().statusCode(404).body("code", equalTo("node_not_found"))
    }

    // --- helpers (mirror MediaTrashSliceTest) ---

    private fun createSub(userId: String, scopeId: String, nodeId: String, sealedName: String): String =
        given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"bucketId":"${TestIds.uuid()}","sealedName":"$sealedName"}""")
            .`when`().post("/api/scopes/$scopeId/nodes/$nodeId/buckets")
            .then().statusCode(200).extract().path("bucketId")

    private fun firstPrimaryId(userId: String, scopeId: String, nodeId: String): String =
        given().header("Authorization", token(userId))
            .`when`().get("/api/scopes/$scopeId/nodes/$nodeId/buckets")
            .then().statusCode(200).extract().path("buckets.find { it.isPrimary == true }.bucketId")

    private fun completeOne(userId: String, scopeId: String, nodeId: String, wrap: String): String {
        val uploadId = startOne(userId, scopeId, wrap)
        return given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"items":[{"mediaId":"${TestBlob.ownerOf(uploadId)}","originalUploadId":"$uploadId","nodeId":"$nodeId"}]}""")
            .`when`().post("/api/scopes/$scopeId/media-batch")
            .then().statusCode(200).body("results[0].error", nullValue())
            .extract().path("results[0].mediaId")
    }

    private fun startOne(userId: String, scopeId: String, wrap: String): String =
        given().header("Authorization", token(userId)).contentType(ContentType.JSON)
            .body("""{"items":[${TestBlob.SMALL.declare(wrap)}]}""")
            .`when`().post("/api/scopes/$scopeId/blob-uploads-batch")
            .then().statusCode(200).body("results[0].uploadId", notNullValue())
            .extract().path("results[0].uploadId")

    private fun seedNode(scopeId: String, kind: String): String {
        val id = UUID.randomUUID().toString()
        QuarkusTransaction.requiringNew().run {
            NodeEntity().apply {
                this.id = id; this.scopeId = scopeId; parentId = scopeId; this.kind = kind
                encryptedName = "bg=="; slugToken = "c2x1Zy0$id".take(120); orderIndex = 0
                wrappedContentKey = if (kind == NodeEntity.MEMORY) "a21lbQ==" else null; createdAt = Instant.now()
            }.persist()
        }
        return id
    }

    private fun seedMemberScope(): Pair<String, String> {
        val (userId, scopeId, _) = seedMemberScopeWithTenant()
        return userId to scopeId
    }

    private fun seedMemberScopeWithTenant(): Triple<String, String, String> {
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
        return Triple(userId, scopeId, tenantId)
    }

    private fun addMemberToScope(scopeId: String, tenantId: String): String {
        val userId = seedUser()
        QuarkusTransaction.requiringNew().run {
            MembershipEntity().apply {
                id = UUID.randomUUID().toString(); this.userId = userId; this.tenantId = tenantId
                this.scopeId = scopeId; role = MembershipEntity.MEMBER; wrappedGrant = "Z3JhbnQ="; createdAt = Instant.now()
            }.persist()
            TenantRoots.join(userId, tenantId)
        }
        return userId
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
